# Next step: Android export bridge for Mi Fitness

Documentation plan only, reviewed 2026-10-15. No receiver, deployment, configuration, credentials, Android device, networking, or existing service changes are part of this work. Implementation and real-data transfer require a separate task.

## Verified baseline

- Inspected `receiver.py`, `test_receiver.py`, `README.md`, `compose.yaml`, `Dockerfile`, and `setup.py`; did not read secret contents or health records.
- `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest -v`: **7 tests passed** (3.800 s), using temporary databases, ephemeral credentials, and synthetic loopback requests.
- `docker compose config --quiet`: **passed**.
- `docker compose ps`: receiver **running, healthy**, published only at `192.168.1.224:8097` to container port 8080.
- Read-only GET `http://192.168.1.224:8097/health`: **HTTP 200**, `{"status": "ok"}`. This checks SQLite accessibility, not comprehensive database integrity, disk capacity, phone reachability, or end-to-end ingestion. No POST was sent to the deployment.

The receiver accepts one strict JSON event per authenticated POST `/events`, maximum 65,536 bytes. Supported types are steps, heart rate samples, sleep intervals, and workout intervals. SQLite persists events. Identical ID/content retries return 200; new events return 201; changed content under an existing ID returns 409. Unknown fields are rejected. There is no update, delete, batch, source metadata, or retrieval API. Storage is protected by filesystem permissions, not encryption; current LAN HTTP does not protect bearer credentials or health data in transit.

## Proposed path and first gate

Wearable → Mi Fitness → on-device Health Connect → small native Kotlin export app → authenticated encrypted transport → existing receiver contract.

Health Connect is an on-device API; this receiver cannot query it remotely. Use the Android Jetpack client and check provider availability before onboarding. Android 14+ integrates Health Connect into the system; compatible older phones require the provider app. Select and test supported OS/provider versions using the [official setup guide](https://developer.android.com/health-and-fitness/health-connect/get-started).

Before building, have the owner verify on their specific phone, Mi Fitness version, region, account, and wearable that Health Connect export is available. With separately authorized phone interaction, sync Mi Fitness and inspect Health Connect entries for each desired type and its actual originating package. Record only a capability matrix (type, available/absent, delay, granularity), never health values in Git. Do not assume every Mi Fitness metric is exported, infer an originating package from the app label, or treat missing records as zero activity.

Xiaomi's [third-party access guidance](https://www.mi.com/uk/support/faq/details/KA-517372/) describes connections that may require cloud sync but does not establish Health Connect support for this installation. If Health Connect export is absent, stop this bridge path and assess an owner-requested [Mi Fitness account export](https://www.mi.com/ph/support/article/KA-11566/) separately. Do not enable cloud sync, install intermediaries, scrape Xiaomi accounts, or request Xiaomi credentials as part of this plan.

## Minimal permissions and consent

Request only enabled categories, with an explanation that data will leave the phone for the owner's server. Manifest permissions use the `android.permission.health.` prefix:

| Health Connect record | Read permission | Receiver mapping |
| --- | --- | --- |
| `StepsRecord` | `READ_STEPS` | `steps`: start, end, integer count |
| `HeartRateRecord` | `READ_HEART_RATE` | one `heart_rate` event per sample: time, bpm |
| `SleepSessionRecord` | `READ_SLEEP` | `sleep`: session start/end; stages omitted |
| `ExerciseSessionRecord` | `READ_EXERCISE` | `workouts`: start/end, stable printable activity name |

Generate runtime scopes with `HealthPermission.getReadPermission(recordClass)` and use the system permission UI. No Health Connect write permissions, exercise routes, location, body sensors, contacts, or Xiaomi login are needed. Add ordinary Internet permission for transport. Provide privacy/rationale screens and the required permission usage entry points; if distributed on Play, complete applicable health declarations. See [setup](https://developer.android.com/health-and-fitness/health-connect/get-started) and [permission reference](https://developer.android.com/reference/android/health/connect/HealthPermissions).

Background reads additionally need `READ_HEALTH_DATA_IN_BACKGROUND`; feature-check availability and request separately. History beyond the default boundary (30 days before first permission grant) needs optional `READ_HEALTH_DATA_HISTORY`, also feature-checked. Reinstallation resets grants. Recheck scopes on every run, support partial grants, and stop denied categories. Without background access, read only while the export UI is visible; without history access, constrain backfill. Page raw reads fully, accepting null or empty final page tokens, and filter to the verified Mi Fitness data origin. These constraints follow [Android's read guide](https://developer.android.com/health-and-fitness/health-connect/read-data).

## Durable export and receiver limitations

Start with a foreground, user-triggered steps-only prototype using synthetic fixtures, then enable other verified types. Use UTC timestamps with a fixed serialization, fixed numeric representation, and a versioned activity mapping. Enforce receiver bounds locally; quarantine invalid records with a non-sensitive reason rather than clamping measurements. Keep source identifiers and export bookkeeping in private phone storage, outside event payloads.

For a source-record export, use deterministic IDs such as `hc1.<SHA-256(origin|recordType|recordId|sampleKey)>` (unambiguous encoding, at most 128 allowed ASCII characters). Heart-rate sample keys must distinguish samples deterministically; persist the mapped bytes for retries. Exclude modification time and payload hash from identity: minting a new ID for a correction would double-count data. Do not add metadata fields to the strict receiver schema.

Bootstrap by obtaining a Changes token before the bounded initial read; persist each page into a durable outbox, then consume changes to catch concurrent writes. Keep per-type/origin cursors and a local record-to-event index. Advance a cursor only after its changes are durably queued or quarantined, independently of network acknowledgements. Handle pagination, expired tokens (normally after 30 days unused), reinstalls and permission changes with bounded rescans and stable IDs. See [Health Connect synchronization guidance](https://developer.android.com/health-and-fitness/health-connect/sync-data).

On 201/200, mark the exact queued payload acknowledged. Retry timeouts, connection failures, and 503 with bounded exponential backoff and jitter. Pause authentication failures for owner repair; quarantine other contract errors, including 409, without endless retries. A lost acknowledgement must resend the same ID and bytes.

**Correction/deletion gate:** Health Connect updates and deletions cannot be faithfully mirrored by this receiver. Quarantine updates to already exported content and tombstones in the local index, show pending reconciliation, and do not silently insert replacement events. A 409 is a conflict, not successful sync. Permission revocation also does not erase previously received data. Full production mirroring needs a separately approved versioned upsert/delete contract and server retention/deletion policy; otherwise explicitly accept an append-only snapshot pilot with possible stale data.

For steps, raw Mi Fitness-only records preserve source identity but must not be advertised as deduplicated daily totals. Compare with source-filtered aggregation during validation; overlapping producer records can inflate sums. Android recommends aggregation for cumulative totals ([read guide](https://developer.android.com/health-and-fitness/health-connect/read-data)). If daily aggregates are desired instead, mutable bucket totals require the future correction contract; do not append successive totals as independent steps.

## Background operation

After foreground validation, use unique WorkManager periodic work (suggested hourly), short bounded pages, persistent checkpoints, and a separate network-constrained upload worker. Periodic work has a 15-minute minimum and may be delayed by Doze, battery policies, or unmet constraints; it is not a realtime schedule ([PeriodicWorkRequest](https://developer.android.com/reference/androidx/work/PeriodicWorkRequest)). OEM restrictions and force-stop can further postpone work. Do not bypass restrictions with permanent foreground services, exact alarms, or automatic battery-setting changes.

Offer Sync now, pause/export category controls, last successful read/upload times, queue count, and permission/connectivity status without displaying measurements in diagnostics. Background upload may send previously consented queued records without a fresh Health Connect read; cancel queued transfers on export withdrawal. An Internet connection does not imply reachability to the private receiver: retain the queue off-LAN, back off, and retry later. Test restart, reboot, offline periods, and delayed Mi Fitness writes; do not promise immediate wearable-to-server delivery.

## Secure transport gate

Do not send real health data or the ingestion token over the current plain HTTP LAN endpoint. A later authorized infrastructure task must supply an HTTPS endpoint with a valid hostname and certificate, or a separately designed authenticated encrypted tunnel with a verified threat model. Recommended design: dedicated LAN TLS ingress forwarding to this receiver across a controlled local hop. Document ownership, trust chain, exposure, and certificate renewal before implementation. This plan provisions none of it and does not widen the current listener or open router ports.

The future app should prohibit cleartext, verify certificates and hostnames, reject redirects that could forward Authorization elsewhere, use connection/read timeouts, and constrain the destination to the explicitly paired server. No trust-all certificate manager or broad cleartext exception. Provision the bearer secret through an owner-controlled secure pairing process; never embed it in an APK, source, analytics, screenshots, URL, or logs. Use Android Keystore-backed encryption for credential storage, private encrypted queue storage, backup exclusion, bounded retention, and deletion controls. Token rotation/revocation must be planned separately: the receiver loads its one shared secret at startup and currently has no per-phone credential management. Keep TLS ingress and client logs free of headers and payloads.

## Implementation order and acceptance criteria

1. Confirm actual Mi Fitness → Health Connect availability and per-type provenance, with owner consent. Decide snapshot limitations versus a future receiver protocol extension.
2. Build a separate Android prototype with fake records and a local fake receiver; test mapping, UTC/DST, multiple heart-rate samples, invalid values, stable IDs, retries, pagination, updates, deletes and token expiry. Do not target deployment tests at the live data store.
3. Validate partial/denied/revoked scopes, unavailable provider/features, historical boundaries, foreground-only fallback, process death during page/outbox commit, offline upload, Doze and force-stop recovery.
4. Separately authorize and implement transport/pairing. Verify certificate/hostname failures and redirects fail closed, secrets and health values stay out of logs/backups, and reconnection preserves exact retries.
5. Only then authorize a small real-data pilot. Compare source counts and intervals locally, verify acknowledgements without logging payloads, surface stale/corrected/deleted records, and document retention and owner-directed deletion. Expand categories only after each passes.

All verification above is point-in-time. Android export, secure transport, and end-to-end ingestion remain unimplemented and unverified.
