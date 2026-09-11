#!/usr/bin/env bash
# Lists what is actually installed on the phone, so you can build folders and
# modes from real package names instead of guesses.
#
#   ./tools/pull-apps.sh              # readable table
#   ./tools/pull-apps.sh --kotlin     # constants to paste into Defaults.kt
#   ./tools/pull-apps.sh --json       # machine readable
#   ./tools/pull-apps.sh --layout     # the home screens as they stand now
#
# Works best with Modes installed, which is what gives you human app names.
# Without it you still get package names via plain adb.

set -euo pipefail

PKG="dev.jaronwilson.modes"
OUT_DIR="${MODES_OUT_DIR:-./build/apps}"
MODE="${1:---table}"

die() { printf 'error: %s\n' "$1" >&2; exit 1; }

command -v adb >/dev/null || die "adb not found. Install platform-tools and try again."

devices=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
[ -n "$devices" ] || die "no device. Check the cable, and that USB debugging is on."
count=$(printf '%s\n' "$devices" | wc -l | tr -d ' ')
[ "$count" -eq 1 ] || die "$count devices connected. Set ANDROID_SERIAL to pick one."

mkdir -p "$OUT_DIR"

installed=$(adb shell pm list packages 2>/dev/null | tr -d '\r' | sed 's/^package://' || true)

if printf '%s\n' "$installed" | grep -qx "$PKG"; then
    # Modes is installed, so ask it for labels as well as package names.
    adb shell am broadcast \
        -a "${PKG}.EXPORT_APPS" \
        -n "${PKG}/.tools.ExportReceiver" >/dev/null 2>&1 || true

    remote="/sdcard/Android/data/${PKG}/files"
    for i in 1 2 3 4 5 6 7 8 9 10; do
        if adb shell "test -f ${remote}/apps.txt" 2>/dev/null; then break; fi
        sleep 0.4
    done

    adb shell "test -f ${remote}/apps.txt" 2>/dev/null \
        || die "Modes did not write the list. Open the app once, then retry."

    for f in apps.txt apps.json layout.txt; do
        adb pull "${remote}/${f}" "${OUT_DIR}/${f}" >/dev/null 2>&1 || true
    done

    case "$MODE" in
        --json)   cat "${OUT_DIR}/apps.json" ;;
        --layout) cat "${OUT_DIR}/layout.txt" ;;
        --kotlin)
            awk 'NF && $0 !~ /^#/ {
                pkg = $NF
                $NF = ""
                name = $0
                gsub(/[ \t]+$/, "", name)
                key = toupper(name)
                gsub(/[^A-Z0-9]+/, "_", key)
                gsub(/^_+|_+$/, "", key)
                if (key != "") printf "    const val %s = \"%s\"\n", key, pkg
            }' "${OUT_DIR}/apps.txt"
            ;;
        *)
            cat "${OUT_DIR}/apps.txt"
            printf '\nSaved to %s\n' "$OUT_DIR"
            ;;
    esac
else
    printf '# Modes is not installed, so app names are not available.\n' >&2
    printf '# Showing package names only. Install the app for a nicer list.\n\n' >&2
    third_party=$(adb shell pm list packages -3 | tr -d '\r' | sed 's/^package://' | sort)
    case "$MODE" in
        --json)
            printf '[\n'
            printf '%s\n' "$third_party" | awk 'NF {
                printf "%s  {\"label\": \"\", \"package\": \"%s\"}", (NR>1 ? ",\n" : ""), $0
            }'
            printf '\n]\n'
            ;;
        *) printf '%s\n' "$third_party" ;;
    esac
fi
