# Android 16 steps prototype (phase 2)

Standalone native Kotlin app, API 36 minimum/target/compile. This implements the foreground steps-only, synthetic prototype portion of `../NEXT_STEP.md`. It does not establish Mi Fitness capability or authorize a real-data pilot. Existing repository services and configuration are untouched.

## Try it without phone data

Launch and press **Run synthetic fixture**. No permission is requested. One invented steps interval is mapped into the receiver's exact five-field schema, queued in memory, then sent twice to a scripted fake receiver (lost acknowledgement, then 201). Only synthetic origin, record/queue counts and acknowledgement status appear in the UI. No networking code, Internet permission, destination, credentials, database, logging or binary wrapper is included.

`Prototype.kt` is the platform-independent mapping/fake layer. `MainActivity.kt` is the optional Health Connect adapter. The live preview never feeds the fake outbox or transport. No real phone access was used during implementation.

## Optional foreground preview and rationale

The owner must first verify that Mi Fitness actually writes steps into Health Connect and inspect the actual source package on their installation. No package is guessed from a label or preselected. Enter that verified package, check the consent box, request the system steps permission, then explicitly press **Preview last 24 hours**. A grant alone does not start a read.

The only requested scope is `HealthPermission.getReadPermission(StepsRecord::class)` / `android.permission.health.READ_STEPS`. There are no write, background, history, location, sensor or Internet permissions. The last-24-hour window avoids historical backfill; Health Connect still enforces its grant boundary. Denial and revocation stop reads. Provider availability is checked before requesting/reading. Permission and resumed state are checked before every page and again before consuming it. Leaving the screen cancels the read and clears synthetic memory; returning never automatically restarts it. Withdraw clears consent and memory; system grants can be revoked in Health Connect settings. Process death intentionally loses all prototype state.

The policy activity supports the Health Connect rationale action and the protected Android 14+ permission-usage alias described in [Android setup](https://developer.android.com/health-and-fitness/health-connect/get-started). The app does not read real records unless the owner explicitly operates this optional path. Distribution and Play declarations are outside this prototype.

Reads use a fixed UTC window, provider-side exact `DataOrigin` filtering, and a second origin check locally. Every raw page is consumed; null and empty final tokens terminate, repeated tokens fail. The UI reports the requested origin and matching/invalid record counts, with no measurements, source IDs or payloads. Zero matching records means absence in that window, not zero activity. Raw source records may overlap and must not be presented as deduplicated daily totals; see [raw reads and aggregation guidance](https://developer.android.com/health-and-fitness/health-connect/read-data).

## Mapping and fake reconciliation

The identity is `hc1.` plus lowercase SHA-256 of four length-prefixed UTF-8 fields: actual origin package, literal `StepsRecord`, Health Connect metadata ID, empty sample key. Each prefix is a four-byte big-endian byte length. Modification time and payload hash are excluded. This fixes delimiter ambiguity and produces 68 permitted ASCII characters. Changing this encoding needs a new identity version. A golden fixture pins the encoding independently of the implementation.

Payload fields are exactly `event_id`, `type`, `start`, `end`, `count`. Instants use canonical UTC `Instant.toString()` and counts use integer decimal notation. Enforce nonempty identity, start before end, and inclusive count bounds 0–1,000,000; quarantine invalid input with non-sensitive reason codes, never clamp. Provenance stays outside the strict payload in the source model; no source metadata is sent to the receiver.

The isolated `FakeOutbox` deduplicates identical content, keeps the original bytes on a correction and quarantines the identity. Tombstones quarantine rather than invent deletion events. Fake 200/201 acknowledges; timeout/503 leaves the exact immutable payload pending; 401/403 pauses; other responses including 409 quarantine. Retries are explicit calls with a three-attempt bound, without timers or background work. No automatic auth recovery. Clear/restart resets this deliberately volatile simulation.

This is a bounded preview, **not durable synchronization**: no Changes token/cursor, token-expiry recovery, encrypted persistent queue, crash-safe page transaction, record index across restarts, backoff scheduler, WorkManager or server export is implemented. Therefore it makes no claims about concurrent writes, catching deletions, restart/reboot/offline recovery or Doze. Those require the later durable synchronization/secure pairing work in `NEXT_STEP.md`. An append-only production receiver cannot mirror corrections/deletions; its reconciliation gate remains in force. Do not substitute a real transport into this prototype or queue real records in the fake path.

## Build and tests

Use a trusted installed JDK 17, Gradle 8.11.1, Android SDK platform 36 and Build Tools 35.0.0 (AGP default), plus dependencies available from Google Maven/Maven Central. Set `ANDROID_HOME` or an untracked `local.properties`. From this directory:

```sh
gradle --offline :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Offline mode requires the pinned plugins/dependencies already cached. If not cached, provision through your trusted Android tooling/repositories before running without `--offline`. No download/bootstrap scripts or wrapper JARs are committed. [AGP 8.10 compatibility](https://developer.android.com/build/releases/agp-8-10-0-release-notes) specifies API 36, Gradle 8.11.1 and JDK 17; [Health Connect release notes](https://developer.android.com/jetpack/androidx/releases/health-connect) list the pinned stable client 1.1.0.

Eight JUnit tests use only synthetic fixtures: golden IDs/strict JSON, origin identity, UTC/DST, count/interval bounds, retries/lost acknowledgement, corrections/deletions/auth/409, origin-filtered pagination with both terminal token forms, permission/foreground loss during a page, and repeated-token rejection. No test contacts a receiver or device.

Verification on the implementation host (2026-10-15):

- Available: `/usr/bin/java`, OpenJDK **1.8.0_504**, ARM64.
- Missing: JDK 17 / `javac`, `gradle`, `kotlinc`, `sdkmanager`, `adb`; `ANDROID_HOME`, `ANDROID_SDK_ROOT` and `JAVA_HOME` unset. No SDK or Gradle cache found in the standard user/system locations inspected.
- **Android compilation, JUnit execution, lint and device checks were not run:** the required toolchain is absent, and Java 8 cannot run AGP 8.10.1. Source/API compatibility remains unverified until those commands pass.
- Manifest XML parsing, synthetic golden digest calculated independently in Python, and Git whitespace checks passed. No binaries or dependencies were downloaded and no real health data, credentials, deployment requests or service changes were used.

On a trusted API 36 emulator/device, first run synthetic mode. Optional owner-operated acceptance checks: unavailable provider, deny/grant/revoke permission, empty/wrong origin, multi-page records, leave during a read, return without automatic read, withdraw consent, process restart. Confirm the APK merged manifest has no Internet/background/history/write permission. These device checks remain pending.
