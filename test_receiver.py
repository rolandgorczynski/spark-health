import concurrent.futures
import contextlib
import copy
import http.client
import io
import json
from pathlib import Path
import secrets
import sqlite3
import tempfile
import threading
import unittest
from receiver import Server, validate, MAX_BODY


class ReceiverTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.db = Path(self.temp.name) / 'test.sqlite3'
        self.token = secrets.token_urlsafe(48)
        self.server = Server(('127.0.0.1', 0), self.db, self.token)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.event = {'event_id': 'synthetic-1', 'type': 'steps', 'start': '2026-01-01T00:00:00Z', 'end': '2026-01-01T01:00:00Z', 'count': 12}

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.temp.cleanup()

    def request(self, event=None, token=True, raw=None, headers=None, path='/events', method='POST'):
        connection = http.client.HTTPConnection(*self.server.server_address, timeout=3)
        body = json.dumps(event if event is not None else self.event) if raw is None else raw
        request_headers = {'Content-Type': 'application/json'}
        if token:
            request_headers['Authorization'] = 'Bearer ' + self.token
        request_headers.update(headers or {})
        connection.request(method, path, body=body if method == 'POST' else None, headers=request_headers)
        response = connection.getresponse()
        result = response.status, json.loads(response.read())
        connection.close()
        return result

    def test_health_and_routes(self):
        self.assertEqual(self.request(path='/health', method='GET', token=False), (200, {'status': 'ok'}))
        self.assertEqual(self.request(path='/missing')[0], 404)
        self.assertEqual(self.request(path='/events', method='GET')[0], 404)

    def test_authentication_and_silent_logs(self):
        output = io.StringIO()
        with contextlib.redirect_stderr(output), contextlib.redirect_stdout(output):
            self.assertEqual(self.request(token=False)[0], 401)
            self.assertEqual(self.request(headers={'Authorization': 'Bearer wrong'})[0], 401)
            self.assertEqual(self.request()[0], 201)
        self.assertEqual(output.getvalue(), '')

    def test_idempotency_conflict_and_persistence(self):
        self.assertEqual(self.request()[0], 201)
        self.assertEqual(self.request(dict(reversed(list(self.event.items()))))[0], 200)
        changed = dict(self.event, count=13)
        self.assertEqual(self.request(changed)[0], 409)
        with sqlite3.connect(self.db) as db:
            self.assertEqual(db.execute('SELECT count(*) FROM events').fetchone()[0], 1)
        second = Server(('127.0.0.1', 0), self.db, self.token)
        second.server_close()
        self.assertEqual(self.request()[0], 200)

    def test_concurrent_duplicates(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
            statuses = list(pool.map(lambda _: self.request()[0], range(6)))
        self.assertEqual(statuses.count(201), 1)
        self.assertEqual(statuses.count(200), 5)

    def test_all_types(self):
        events = [
            dict(self.event, event_id='steps'),
            {'event_id': 'hr', 'type': 'heart_rate', 'time': '2026-01-01T00:00:00+01:00', 'bpm': 70.5},
            {k: v for k, v in dict(self.event, event_id='sleep', type='sleep').items() if k != 'count'},
            {k: v for k, v in dict(self.event, event_id='workout', type='workouts', activity='walking').items() if k != 'count'},
        ]
        for event in events:
            with self.subTest(kind=event['type']):
                self.assertEqual(self.request(event)[0], 201)

    def test_invalid_events(self):
        invalid = [None, [], {}, dict(self.event, count=True), dict(self.event, count=-1),
                   dict(self.event, count=1.5), dict(self.event, extra='no'),
                   dict(self.event, start=self.event['end']), dict(self.event, start='2026-01-01'),
                   dict(self.event, event_id=''), dict(self.event, type=[]),
                   {'event_id': 'hr', 'type': 'heart_rate', 'time': self.event['start'], 'bpm': float('nan')},
                   {'event_id': 'hr', 'type': 'heart_rate', 'time': self.event['start'], 'bpm': 401},
                   {'event_id': 'w', 'type': 'workouts', 'start': self.event['start'], 'end': self.event['end'], 'activity': ''}]
        for event in invalid:
            with self.subTest(event=event):
                self.assertEqual(self.request(raw=json.dumps(event))[0], 400)
        for raw in ('{', '\xff', '{"type":"steps","type":"sleep"}', '[' * 2000):
            self.assertEqual(self.request(raw=raw.encode('utf-8'))[0], 400)

    def test_body_and_content_type(self):
        self.assertEqual(self.request(headers={'Content-Type': 'text/plain'})[0], 415)
        self.assertEqual(self.request(raw='x' * (MAX_BODY + 1))[0], 413)
        self.assertEqual(self.request(raw='')[0], 413)
        self.assertEqual(self.request(headers={'Transfer-Encoding': 'chunked'})[0], 400)


if __name__ == '__main__':
    unittest.main()
