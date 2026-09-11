#!/usr/bin/env bash
# Sets up and boots an Android emulator for taking screenshots.
#
#   ./tools/emulator.sh --check    # will this machine run one? (default)
#   ./tools/emulator.sh --install  # download the emulator and a system image
#   ./tools/emulator.sh --create   # make the AVD
#   ./tools/emulator.sh --start    # boot it, headless
#
# Read --check first. An emulator without hardware virtualisation technically
# runs and is too slow to be worth anything, so this refuses rather than
# wasting an hour of your evening proving it.

set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/android-sdk}"
AVD_NAME="modes-pixel"
API="35"
IMAGE="system-images;android-${API};google_apis;x86_64"
NEEDED_GB=6

ok()   { printf '  yes  %s\n' "$1"; }
bad()  { printf '  NO   %s\n' "$1"; }
info() { printf '       %s\n' "$1"; }

check() {
    local fail=0
    printf 'Can this machine run an Android emulator?\n\n'

    if [ -e /dev/kvm ] && [ -r /dev/kvm ]; then
        ok "hardware virtualisation (/dev/kvm)"
    elif grep -qE '^flags.*\b(vmx|svm)\b' /proc/cpuinfo 2>/dev/null; then
        bad "/dev/kvm missing, but the CPU supports it"
        info "try: sudo apt install qemu-kvm && sudo usermod -aG kvm \$USER"
        fail=1
    else
        bad "no hardware virtualisation on this CPU"
        if grep -q hypervisor /proc/cpuinfo 2>/dev/null; then
            info "this machine is itself a VM, and nested virtualisation is off"
            info "enable it on the host, or use a machine with KVM"
        fi
        info "without it the emulator falls back to software and is unusably slow"
        fail=1
    fi

    local free_gb
    free_gb=$(df -BG --output=avail "$HOME" | tail -1 | tr -dc '0-9')
    if [ "${free_gb:-0}" -ge "$NEEDED_GB" ]; then
        ok "disk space (${free_gb}G free)"
    else
        bad "disk space (${free_gb}G free, needs about ${NEEDED_GB}G)"
        info "the system image alone is roughly 1.5G, plus the emulator and the AVD"
        fail=1
    fi

    if [ -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
        ok "android sdk at $SDK"
    else
        bad "no sdkmanager at $SDK"
        fail=1
    fi

    printf '\n'
    if [ "$fail" -eq 0 ]; then
        printf 'Good to go:  ./tools/emulator.sh --install\n'
    else
        printf 'Not on this machine. Use a real phone instead:\n'
        printf '  ./tools/screenshots.sh\n'
    fi
    return "$fail"
}

case "${1:---check}" in
    --check) check ;;
    --install)
        check || { printf '\nRefusing to install. See above.\n'; exit 1; }
        "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" "emulator" "$IMAGE"
        ;;
    --create)
        printf 'no\n' | "$SDK/cmdline-tools/latest/bin/avdmanager" create avd \
            -n "$AVD_NAME" -k "$IMAGE" -d "pixel_6" --force
        # A screenshot wants a real phone's proportions, not the default.
        cfg="$HOME/.android/avd/${AVD_NAME}.avd/config.ini"
        {
            echo "hw.lcd.density=440"
            echo "hw.lcd.height=2400"
            echo "hw.lcd.width=1080"
            echo "hw.keyboard=yes"
        } >> "$cfg"
        printf 'Created %s\n' "$AVD_NAME"
        ;;
    --start)
        "$SDK/emulator/emulator" -avd "$AVD_NAME" \
            -no-snapshot -no-boot-anim -gpu swiftshader_indirect -no-window &
        printf 'Booting. Waiting for the device...\n'
        "$SDK/platform-tools/adb" wait-for-device
        until [ "$("$SDK/platform-tools/adb" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
            sleep 3
        done
        printf 'Up. Now:  ./tools/install.sh && ./tools/screenshots.sh\n'
        ;;
    *) printf 'unknown option: %s\n' "$1"; exit 1 ;;
esac
