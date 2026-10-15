"""Private, bounded HTTP ingestion using only the Python standard library."""
import datetime as dt
import hashlib
import hmac
import json
import math
import os
from pathlib import Path
import sqlite3
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAX_BODY = 65536


def timestamp(value):
    if not isinstance(value, str):
        raise ValueError()
    parsed = dt.datetime.fromisoformat(value.replace('Z', '+00:00'))
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise ValueError()
    return parsed


def number(value, low, high):
    if type(value) not in (int, float) or not math.isfinite(value) or not low <= value <= high:
        raise ValueError()


def validate(event):
    if not isinstance(event, dict):
        raise ValueError()
    kind = event.get('type')
    fields = {
        'steps': {'start', 'end', 'count'},
        'heart_rate': {'time', 'bpm'},
        'sleep': {'start', 'end'},
        'workouts': {'start', 'end', 'activity'},
    }
    if not isinstance(kind, str) or kind not in fields:
        raise ValueError()
    if set(event) != {'event_id', 'type'} | fields[kind]:
        raise ValueError()
    eid = event['event_id']
    if not isinstance(eid, str) or not 1 <= len(eid) <= 128 or not all(c.isascii() and (c.isalnum() or c in '-_.:') for c in eid):
        raise ValueError()
    if kind == 'heart_rate':
        timestamp(event['time'])
        number(event['bpm'], 1, 400)
    else:
        if timestamp(event['start']) >= timestamp(event['end']):
            raise ValueError()
    if kind == 'steps':
        if type(event['count']) is not int:
            raise ValueError()
        number(event['count'], 0, 1000000)
    if kind == 'workouts':
        activity = event['activity']
        if not isinstance(activity, str) or not 1 <= len(activity) <= 80 or not activity.isprintable():
            raise ValueError()
    return json.dumps(event, sort_keys=True, separators=(',', ':'), allow_nan=False)


class Server(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, db_path, token):
        if len(token) < 32:
            raise ValueError('Token must have at least 32 characters')
        self.token_digest = hashlib.sha256(token.encode()).digest()
        self.db_path = str(db_path)
        with self.connect() as db:
            db.execute('PRAGMA journal_mode=WAL')
            db.execute('CREATE TABLE IF NOT EXISTS events (event_id TEXT PRIMARY KEY, payload TEXT NOT NULL, received_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)')
        super().__init__(address, Handler)

    def connect(self):
        return sqlite3.connect(self.db_path, timeout=10)


class Handler(BaseHTTPRequestHandler):
    server_version = 'PrivateReceiver'
    sys_version = ''

    def setup(self):
        super().setup()
        self.connection.settimeout(10)

    def log_message(self, *_args):
        pass  # Never log headers, request paths, payloads, or credentials.

    def reply(self, status, data):
        body = json.dumps(data).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Connection', 'close')
        self.end_headers()
        self.wfile.write(body)
        self.close_connection = True

    def do_GET(self):
        if self.path == '/health':
            try:
                with self.server.connect() as db:
                    db.execute('SELECT 1 FROM events LIMIT 1').fetchone()
                self.reply(200, {'status': 'ok'})
            except sqlite3.Error:
                self.reply(503, {'error': 'unavailable'})
        else:
            self.reply(404, {'error': 'not_found'})

    def do_POST(self):
        if self.path != '/events':
            return self.reply(404, {'error': 'not_found'})
        auth = self.headers.get_all('Authorization', [])
        supplied = auth[0] if len(auth) == 1 else ''
        digest = hashlib.sha256(supplied.removeprefix('Bearer ').encode()).digest()
        if not supplied.startswith('Bearer ') or not hmac.compare_digest(digest, self.server.token_digest):
            return self.reply(401, {'error': 'unauthorized'})
        if self.headers.get_content_type() != 'application/json':
            return self.reply(415, {'error': 'application_json_required'})
        lengths = self.headers.get_all('Content-Length', [])
        if self.headers.get('Transfer-Encoding') or len(lengths) != 1:
            return self.reply(400, {'error': 'invalid_framing'})
        try:
            size = int(lengths[0])
            if size < 1 or size > MAX_BODY:
                return self.reply(413, {'error': 'body_size'})
            raw = self.rfile.read(size)
            if len(raw) != size:
                raise ValueError()
            def unique(pairs):
                result = {}
                for key, value in pairs:
                    if key in result:
                        raise ValueError()
                    result[key] = value
                return result
            event = json.loads(raw.decode('utf-8'), object_pairs_hook=unique)
            payload = validate(event)
        except (ValueError, TypeError, OverflowError, RecursionError):
            return self.reply(400, {'error': 'invalid_event'})
        try:
            with self.server.connect() as db:
                cursor = db.execute('INSERT OR IGNORE INTO events(event_id,payload) VALUES (?,?)', (event['event_id'], payload))
                if cursor.rowcount:
                    status, result = 201, 'created'
                elif db.execute('SELECT payload FROM events WHERE event_id=?', (event['event_id'],)).fetchone()[0] == payload:
                    status, result = 200, 'duplicate'
                else:
                    status, result = 409, 'event_id_conflict'
            self.reply(status, {'status': result})
        except sqlite3.Error:
            self.reply(503, {'error': 'unavailable'})


if __name__ == '__main__':
    os.umask(0o077)
    token = Path(os.environ.get('TOKEN_FILE', '/run/secrets/ingest_token')).read_text().strip()
    db_path = Path(os.environ.get('DB_PATH', '/data/health.sqlite3'))
    db_path.parent.mkdir(parents=True, exist_ok=True)
    Server(('0.0.0.0', 8080), db_path, token).serve_forever()
