# Spark Health — step 1

Private LAN ingestion receiver using Python's standard library and persistent SQLite. This is a receiver foundation with artificial examples; it does not connect to Health Connect, Android, Mi Fitness, or any cloud service. No real health data is included or needed.

## Start and stop

Run from this directory:

```sh
python3 setup.py
docker compose up -d --build
docker compose ps
# Stop and remove this project's container/network; retain local data and secret:
docker compose down
```

Setup generates a random 384-bit bearer token in `secrets/ingest_token` (mode 0600), creates private storage, and records your UID/GID in `.env`. It preserves existing tokens. These paths are excluded from Git; the Docker build context includes only the receiver and Dockerfile. Run setup as the account that will own the data. Docker Compose mounts the token as a file, never as an environment value or command argument. Keep backups private.

The only published listener is **192.168.1.224:8097**. The host must already own that address; this project does not configure networking. Compose creates its own default bridge network. Do not change the binding to `0.0.0.0`. The container's internal listener uses port 8080. No privileged mode, host network, or other stacks are involved. The initial build requires Docker Hub access for the Python image; ingestion and storage stay local and have no cloud dependency.

HTTP bearer authentication assumes a trusted private LAN: HTTP itself does not encrypt traffic. Step 1 supplies no public endpoint, TLS termination, phone integration, or outbound application calls. Avoid shell tracing (`set -x`) and verbose curl when using credentials.

## Artificial curl checks

Health returns only availability, without authentication or stored data:

```sh
curl --fail --silent --show-error http://192.168.1.224:8097/health
```

Use this Bash function to pass the token through curl's standard input, keeping it out of command arguments and shell history. Do not print the token:

```bash
send_artificial() {
  { printf 'Authorization: Bearer '; tr -d '\n' < secrets/ingest_token; printf '\n'; } |
    curl --silent --show-error --include \
      --header @- --header 'Content-Type: application/json' \
      --data-binary @examples/artificial-steps.json \
      http://192.168.1.224:8097/events
}
send_artificial # 201 created
send_artificial # 200 duplicate, no additional row
curl --silent --show-error --include \
  --header 'Content-Type: application/json' \
  --data-binary @examples/artificial-steps.json \
  http://192.168.1.224:8097/events # 401, no token
```

## Event contract

POST `/events`, `Authorization: Bearer <token>`, `Content-Type: application/json`. One JSON object per request, at most 65,536 bytes. Fields are exact; unknown fields and duplicate JSON keys are rejected. Every event requires `event_id` (1–128 ASCII letters, digits, `-_.:`) and `type`:

| type | Required additional fields | Validation |
| --- | --- | --- |
| `steps` | `start`, `end`, `count` | integer count 0–1,000,000 |
| `heart_rate` | `time`, `bpm` | finite number 1–400 |
| `sleep` | `start`, `end` | interval |
| `workouts` | `start`, `end`, `activity` | printable activity string, 1–80 characters |

Times use ISO 8601 with an explicit timezone, e.g. `2026-01-01T10:00:00Z`; intervals must end after they start. These are ingestion bounds, not medical interpretation. Sleep stages and workout details are outside step 1.

Additional artificial objects:

```json
{"event_id":"artificial-hr-001","type":"heart_rate","time":"2026-01-01T10:00:00Z","bpm":72}
{"event_id":"artificial-sleep-001","type":"sleep","start":"2026-01-01T00:00:00Z","end":"2026-01-01T07:00:00Z"}
{"event_id":"artificial-workout-001","type":"workouts","start":"2026-01-01T10:00:00Z","end":"2026-01-01T10:30:00Z","activity":"walking"}
```

SQLite stores canonical event JSON with a unique primary key on event ID and a server receipt time. A retry with the same ID and canonical JSON returns 200; changed content under that ID returns 409 without overwriting. Field order and whitespace do not affect retries; different timestamp representations or numeric representations may count as changed content. Responses: 201 created, 200 duplicate, 400 invalid event/framing, 401 unauthorized, 404 unknown route, 413 size limit, 415 wrong media type, 503 storage unavailable. GET `/health` checks database accessibility. No data retrieval endpoint is provided.

Database files live in `data/` on the host and survive container rebuilds and `compose down`. Request headers, payloads, paths, and credentials are not logged. Database storage is private by filesystem permissions, not encrypted. Stop the service before copying the entire `data/` directory for a consistent local backup.

## Automated checks

```sh
python3 -m unittest -v
# Validate configuration without printing interpolated configuration:
docker compose config --quiet
```

Tests use ephemeral tokens, temporary SQLite databases, and loopback HTTP with artificial events only. They cover all types, malformed input, bounds, auth, silent request logs, health, persistence, conflicts, concurrent retries, and request size/media type limits.
