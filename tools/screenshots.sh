#!/usr/bin/env bash
# Captures screenshots from a connected phone (or a running emulator) into
# docs/screenshots/, ready for the README.
#
#   ./tools/screenshots.sh           # walk through every screen
#   ./tools/screenshots.sh home now  # just those two
#   ./tools/screenshots.sh --list    # what it can capture
#
# It tells you what to open, you navigate, you press Enter. Driving Compose
# through adb taps is possible and breaks every time a layout moves, so this
# asks instead.

set -euo pipefail

PKG="dev.jaronwilson.modes"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/docs/screenshots"
ADB="${ANDROID_HOME:-$HOME/android-sdk}/platform-tools/adb"
command -v adb >/dev/null 2>&1 && ADB=adb

# name|what to do|caption for the README
SHOTS=(
"home|Press the home button to show the Modes home screen|The home screen: today's calendar, then the mode's apps as folders"
"home-folder|Tap a folder to expand it|A folder open in place. No grid, no icons"
"now|Open Modes, stay on the Now tab|Now: the running mode, why it is running, and what is waiting"
"modes|Tap the Modes tab|The five modes"
"mode-edit|Tap a mode, for example Work|What gets through, and what happens if you reach for something else"
"home-layout|In that mode, tap Arrange home screen and folders|Folder switches for one mode. Off means unreachable, not just hidden"
"folders|Tap Edit the folder library|The shared folder library, used by every mode"
"rules|Tap the Rules tab|Rules: people who always get through, calendar and time rules, notification sorting"
"digest|Tap the Waiting tab|What is being held, and what was held over the last day"
"speedbump|With Work running, open an app the mode set aside|The pause. Not a lock"
)

if [ "${1:-}" = "--list" ]; then
    printf '%-14s %s\n' "NAME" "SCREEN"
    for s in "${SHOTS[@]}"; do
        IFS='|' read -r name what _ <<<"$s"
        printf '%-14s %s\n' "$name" "$what"
    done
    exit 0
fi

command -v "$ADB" >/dev/null 2>&1 || { printf 'error: adb not found\n' >&2; exit 1; }
devices=$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')
if [ -z "$devices" ]; then
    printf 'error: no device.\n' >&2
    printf '  Plug the phone in with USB debugging on, or start an emulator:\n' >&2
    printf '    ./tools/emulator.sh --check\n' >&2
    exit 1
fi

"$ADB" shell "pm list packages $PKG" | grep -q . || {
    printf 'error: Modes is not installed. Run ./tools/install.sh\n' >&2; exit 1; }

mkdir -p "$OUT"
wanted=("$@")

capture() {
    local name="$1" what="$2"
    printf '\n  %s\n  %s\n' "$name" "$what"
    printf '  press Enter to capture, s to skip: '
    read -r reply </dev/tty
    [ "$reply" = "s" ] && { printf '  skipped\n'; return; }
    "$ADB" exec-out screencap -p > "$OUT/$name.png"
    # Full resolution is far too heavy for a README.
    if command -v magick >/dev/null 2>&1; then
        magick "$OUT/$name.png" -resize 420x "$OUT/$name.png"
    elif command -v convert >/dev/null 2>&1; then
        convert "$OUT/$name.png" -resize 420x "$OUT/$name.png"
    fi
    printf '  saved %s (%s)\n' "$name.png" "$(du -h "$OUT/$name.png" | cut -f1)"
}

printf 'Capturing to %s\n' "$OUT"
for s in "${SHOTS[@]}"; do
    IFS='|' read -r name what caption <<<"$s"
    if [ ${#wanted[@]} -gt 0 ]; then
        match=0
        for w in "${wanted[@]}"; do [ "$w" = "$name" ] && match=1; done
        [ "$match" -eq 1 ] || continue
    fi
    capture "$name" "$what"
done

# A markdown block to paste into the README, captions already written.
{
    printf '## Screenshots\n\n'
    for s in "${SHOTS[@]}"; do
        IFS='|' read -r name _ caption <<<"$s"
        [ -f "$OUT/$name.png" ] || continue
        printf '### %s\n\n![%s](docs/screenshots/%s.png)\n\n%s\n\n' \
            "$(printf '%s' "$name" | tr '-' ' ')" "$name" "$name" "$caption"
    done
} > "$OUT/README-block.md"

printf '\nDone. Paste %s into the README.\n' "docs/screenshots/README-block.md"
