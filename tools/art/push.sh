#!/bin/sh
# Copies the rendered paintings (art/out, from render.py) to Rattan's own storage on the TV, where
# the screensaver reads them; only new or changed files travel, and pictures no longer in the
# catalog are removed.
# Usage: tools/art/push.sh [ip:port]
set -eu
TV="${1:-192.168.178.59:5555}"
DIR=/sdcard/Android/data/site.gehlot.rattan/files/art
cd "$(dirname "$0")/../../art/out"

adb -s "$TV" shell "ls -d $DIR" >/dev/null 2>&1 || {
    echo "Rattan has not created $DIR yet; start the screensaver once, then run this again." >&2
    exit 1
}
LIST=$(mktemp)
# The catalog names every picture; stray files (contact sheets, sync copies) never travel.
python3 -I -c 'import json; print("\n".join(w["file"] for w in json.load(open("catalog.json"))["works"]))' > "$LIST"
# shellcheck disable=SC2046
adb -s "$TV" push --sync $(cat "$LIST") "$DIR/" | tail -1
adb -s "$TV" shell "ls $DIR" | tr -d '\r' | grep -E '\.jpg$' | grep -vxF -f "$LIST" |
    while read -r f; do adb -s "$TV" shell rm "$DIR/$f" </dev/null; done
adb -s "$TV" push -q catalog.json "$DIR/catalog.json"
rm "$LIST"
echo "$(adb -s "$TV" shell "ls $DIR | grep -c jpg" | tr -d '\r') paintings on the TV"
