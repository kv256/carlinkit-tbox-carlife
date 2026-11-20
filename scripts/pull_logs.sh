#!/usr/bin/env bash
# Pull persisted CarLife logs (/cache/carlife/logs) + live device audio/BT state into git-ignored logs/<timestamp>/.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ADB_BIN:-$REPO/../tools/linux/platform-tools/adb}"
DEFAULT_IPS=()
SERIAL="${1:-}"

if [ -z "$SERIAL" ]; then
  SERIAL=$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1; exit}')
fi
if [ -z "$SERIAL" ]; then
  for ip in ${DEFAULT_IPS[@]+"${DEFAULT_IPS[@]}"}; do
    if "$ADB" connect "$ip" 2>/dev/null | grep -q "connected to"; then
      SERIAL="$ip"
      break
    fi
  done
fi
[ -n "$SERIAL" ] || { echo "ERROR: No Tbox ADB device reachable." >&2; exit 1; }

TS="$(date '+%Y%m%d-%H%M%S')"
OUT="$REPO/logs/$TS"
mkdir -p "$OUT"
echo "Pulling logs from $SERIAL -> $OUT"

for f in $("$ADB" -s "$SERIAL" shell "su -c 'ls /cache/carlife/logs/'" | tr -d '\r'); do
  [ -n "$f" ] || continue
  "$ADB" -s "$SERIAL" shell "su -c 'cat /cache/carlife/logs/$f'" > "$OUT/$f"
done

"$ADB" -s "$SERIAL" shell "su -c '
  echo ===PROPS===
  echo carlife=\$(getprop persist.tbox.carlife) media=\$(getprop persist.tbox.carlife.audio.media) navi=\$(getprop persist.tbox.carlife.audio.navi) call=\$(getprop persist.tbox.carlife.audio.call)
  echo ===BT_PRIORITIES===
  settings list global | grep -E \"bluetooth_(a2dp_sink|headset)_priority_\"
  echo ===STATUS===
  cat /cache/carlife/status 2>/dev/null || true
  echo ===PS===
  ps -ef | grep -E \"carlife-daemon|org\\.carlife\\.hulab|sdsdk816\" | grep -v grep
  echo ===TOMBSTONES===
  ls -la /data/tombstones/ 2>/dev/null || true
'" > "$OUT/device_state.txt"

ls -lh "$OUT"
