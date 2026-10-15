#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "$0")/../.." && pwd)
export JAVA_HOME="${JAVA_HOME:-$root/.tools/jdk}"
export ANDROID_HOME="${ANDROID_HOME:-$root/.tools/sdk}"
gradle_bin="${GRADLE_BIN:-$root/.tools/gradle-8.11.1/bin/gradle}"
args=()
if [[ $(uname -m) == aarch64 ]]; then
    : "${AAPT2_OVERRIDE:=$root/.tools/aapt2}"
    [[ -x $AAPT2_OVERRIDE ]] || { echo 'ARM64 requires the local aapt2 QEMU launcher; see README.' >&2; exit 1; }
    args+=("-Pandroid.aapt2FromMavenOverride=$AAPT2_OVERRIDE")
fi
"$gradle_bin" -p "$root/android" "${args[@]}" --max-workers=4 :app:testDebugUnitTest :app:assembleDebug :app:lintDebug "$@"
apk="$root/android/app/build/outputs/apk/debug/app-debug.apk"
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose "$apk"
stat -c '%n %s bytes' "$apk"
sha256sum "$apk"
