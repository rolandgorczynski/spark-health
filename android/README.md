# Android 16 steps prototype (phase 2)

Standalone native Kotlin app, API 36 minimum/target/compile. This implements the foreground steps-only, synthetic prototype portion of `../NEXT_STEP.md`. It does not establish Mi Fitness capability or authorize a real-data pilot. Existing repository services and configuration are untouched.

## Try it without phone data

Launch and press **Run synthetic fixture**. No permission is requested. One invented steps interval is mapped into the receiver's exact five-field schema, queued in memory, then sent twice to a scripted fake receiver (lost acknowledgement, then 201). Only synthetic origin, record/queue counts and acknowledgement status appear in the UI. No networking code, Internet permission, destination, credentials, database, logging is included.

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

From the repository root on this Ubuntu 24.04 ARM64 host (Python 3, curl, unzip, tar, apt and dpkg-deb are available):

```sh
python3 android/scripts/bootstrap-arm64.py
android/scripts/build-debug.sh
# Once dependencies are cached:
android/scripts/build-debug.sh --offline
```

Bootstrap downloads checksum-pinned Temurin JDK 17, official Gradle 8.11.1 and Google command-line tools 19.0 into ignored `.tools/`. It accepts SDK licenses and installs platform 36 and Build Tools 35.0.0. Gradle may also provision platform-tools; no adb command is used. Dependencies come from Google Maven/Maven Central/plugin portal. Google Linux build tools are x86-64: on ARM64, the script downloads Ubuntu QEMU and its libraries with `apt download`, extracts them locally, and uses Ubuntu amd64 runtime libraries verified against the HTTPS package index. It never installs system packages or registers binfmt. These Ubuntu dependencies follow the configured package repositories, while JDK/Gradle/command-line archives are pinned. The experimental AGP `android.aapt2FromMavenOverride` points to the local QEMU launcher.

On x86-64, install a trusted native JDK 17, Gradle 8.11.1, SDK platform 36 and Build Tools 35.0.0, then:

```sh
JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/sdk \
  GRADLE_BIN=/path/to/gradle-8.11.1/bin/gradle android/scripts/build-debug.sh
```

The build script runs `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug`, verifies the debug APK signature and prints its full path, size and SHA-256. Source API levels are all 36 (Android 16); this APK requires Android 16 or later. Tooling, debug signing keys, credentials, local SDK configuration and APK/build outputs stay untracked. The generated debug key lives in the standard user Android directory; it is for local debug installation only.

Reports: `android/app/build/reports/tests/testDebugUnitTest/index.html` and `android/app/build/reports/lint-results-debug.html`. APK: `android/app/build/outputs/apk/debug/app-debug.apk`.

Eight JUnit tests use artificial fixtures only, covering golden identity/JSON, UTC/DST, invalid records, retries, corrections/deletions, auth/conflicts, filtered pagination, permission/foreground loss and loop rejection. Android 16 UI insets keep the main and rationale screens clear of system bars and the keyboard.

Device installation/launch and interaction checks remain pending: no phone, real records, Mi Fitness login or emulator was used. Static APK/signature checks establish packaging compatibility, not runtime behavior. Any future device check must use an isolated API 36 emulator and artificial data only.

Verified on 2026-10-15: Kotlin compilation, debug assembly, all **8 tests** (zero failures/skips), lint (**0 errors, 22 warnings**), APK v2 signature and 16 KiB zip alignment passed. Remaining lint warnings are English UI strings and newer dependency availability. An initial concurrent offline attempt ran before lint dependencies finished downloading and failed; the subsequent sequential offline build passed. Bootstrap was rerun successfully from the installed toolchain.

Additional packaging checks from the repository root:

```sh
.tools/aapt2 dump badging android/app/build/outputs/apk/debug/app-debug.apk
LD_LIBRARY_PATH="$PWD/.tools/qemu/usr/lib/aarch64-linux-gnu" \
  .tools/qemu/usr/bin/qemu-x86_64 -L "$PWD/.tools/amd64" \
  .tools/sdk/build-tools/35.0.0/zipalign -c -P 16 -v 4 \
  android/app/build/outputs/apk/debug/app-debug.apk
```

Packaged badging confirms `local.spark.steps`, the launcher activity, min/target API 36 and only READ_STEPS plus AndroidX's signature-protected internal receiver permission. There are no native `.so` libraries. `apksigner verify` is part of the build script. Debug signing keys and ZIP timestamps can change between hosts, so the reported artifact digest identifies this build, not a promise of byte-identical rebuilds.
