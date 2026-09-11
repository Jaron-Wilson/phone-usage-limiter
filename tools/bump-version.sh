#!/usr/bin/env bash
# Moves the version on by 0.0.1 and increments the build number.
# Run this before producing an APK you intend to install.
#
#   ./tools/bump-version.sh            # 0.1.1 -> 0.1.2
#   ./tools/bump-version.sh --minor    # 0.1.1 -> 0.2.0
#   ./tools/bump-version.sh --show     # print the current version

set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
file="$root/version.properties"
[ -f "$file" ] || { printf 'error: %s not found\n' "$file" >&2; exit 1; }

name=$(grep -E '^versionName=' "$file" | cut -d= -f2 | tr -d ' \r')
code=$(grep -E '^versionCode=' "$file" | cut -d= -f2 | tr -d ' \r')

case "${1:-}" in
    --show) printf '%s (build %s)\n' "$name" "$code"; exit 0 ;;
esac

IFS=. read -r major minor patch <<<"$name"
: "${major:=0}" "${minor:=0}" "${patch:=0}"

case "${1:-}" in
    --minor) minor=$((minor + 1)); patch=0 ;;
    --major) major=$((major + 1)); minor=0; patch=0 ;;
    *)       patch=$((patch + 1)) ;;
esac

new_name="${major}.${minor}.${patch}"
new_code=$((code + 1))

cat > "$file" <<EOF
# Bumped by tools/bump-version.sh on every APK build.
versionName=${new_name}
versionCode=${new_code}
EOF

printf '%s -> %s (build %s)\n' "$name" "$new_name" "$new_code"
