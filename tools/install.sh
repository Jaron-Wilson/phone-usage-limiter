#!/usr/bin/env bash
# Installs the release APK and, if it fails, says why in plain words rather
# than leaving you with "App not installed".
#
#   ./tools/install.sh              # install or upgrade
#   ./tools/install.sh --clean      # uninstall first, losing your settings
#   ./tools/install.sh --check      # report only, change nothing

set -euo pipefail

PKG="dev.jaronwilson.modes"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE="${1:-}"

die() { printf 'error: %s\n' "$1" >&2; exit 1; }
note() { printf '  %s\n' "$1"; }

APK=$(ls -t "$ROOT"/app/build/outputs/apk/release/Modes-v*-release.apk 2>/dev/null | head -1 || true)
[ -n "$APK" ] || die "no release APK. Run: ./gradlew :app:assembleRelease"

printf 'APK:  %s\n' "$(basename "$APK")"
printf 'Size: %s\n' "$(du -h "$APK" | cut -f1)"
printf 'SHA:  %s\n' "$(sha256sum "$APK" | cut -c1-16)..."

command -v adb >/dev/null || die "adb not found. Install platform-tools."
devices=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
[ -n "$devices" ] || die "no device. Check the cable, and that USB debugging is on."

installed=$(adb shell "pm list packages $PKG" 2>/dev/null | tr -d '\r' || true)
if [ -n "$installed" ]; then
    current=$(adb shell "dumpsys package $PKG | grep versionName" 2>/dev/null \
        | tr -d '\r' | head -1 | sed 's/.*versionName=//' || true)
    printf 'Installed already: %s\n' "${current:-unknown}"
else
    printf 'Installed already: no\n'
fi

if [ "$MODE" = "--check" ]; then exit 0; fi

if [ "$MODE" = "--clean" ] && [ -n "$installed" ]; then
    printf 'Removing the old copy...\n'
    adb uninstall "$PKG" >/dev/null 2>&1 || true
fi

printf 'Installing...\n'
out=$(adb install -r "$APK" 2>&1 || true)

if printf '%s' "$out" | grep -q "Success"; then
    printf 'Done.\n'
    exit 0
fi

printf '\nInstall failed. Android said:\n%s\n\n' "$out"

case "$out" in
    *UPDATE_INCOMPATIBLE*|*INCONSISTENT_CERTIFICATES*|*signatures*do*not*match*)
        note "The copy on the phone was signed with a different key."
        note "This build moved from the debug key to a proper release key, so"
        note "that is expected exactly once. Settings will be lost:"
        note ""
        note "    ./tools/install.sh --clean"
        ;;
    *INSTALL_FAILED_VERSION_DOWNGRADE*)
        note "The phone has a newer build than this one."
        note "Bump and rebuild:  ./tools/bump-version.sh && ./gradlew :app:assembleRelease"
        ;;
    *INSUFFICIENT_STORAGE*)
        note "Not enough free space on the phone."
        ;;
    *INSTALL_FAILED_USER_RESTRICTED*|*verification*|*VERIFICATION_FAILURE*)
        note "Play Protect blocked it, which it often does for sideloaded apps."
        note "On the phone: Play Store > profile > Play Protect > settings >"
        note "turn off 'Scan apps with Play Protect', install, then turn it"
        note "back on. Or tap 'More details' then 'Install anyway' on the"
        note "warning itself."
        ;;
    *INSTALL_FAILED_INVALID_APK*|*INSTALL_PARSE_FAILED*)
        note "The file is damaged, usually a truncated transfer. Compare:"
        note "    sha256sum '$APK'"
        note "against the copy on the phone before installing again."
        ;;
    *no\ devices*|*device\ offline*)
        note "The phone dropped off. Reconnect and retry."
        ;;
    *)
        note "Unrecognised. The exact text above is the thing worth searching."
        ;;
esac
exit 1
