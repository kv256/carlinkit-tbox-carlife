#!/usr/bin/env bash
# Pull persisted CarLife logs (/cache/carlife/logs) + live device audio/BT state into git-ignored logs/<timestamp>/.
# Usage: scripts/pull_logs.sh [--serial HOST:PORT | adb-serial] [--full]
#   --full  Also pull the latest persisted Android/kernel logcat from /storage/emulated/0/x7_Log/ and tombstones.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
# Local, git-ignored settings (see .env.example): TBOX_ADB_IPS, ADB_BIN.
if [ -f "$REPO/.env" ]; then set -a; . "$REPO/.env"; set +a; fi
ADB="${ADB_BIN:-$REPO/../tools/linux/platform-tools/adb}"
# Wi-Fi ADB addresses tried in order when no device is connected and none is given.
read -r -a DEFAULT_IPS <<< "${TBOX_ADB_IPS:-}"
SERIAL=""
FULL=0

while [ $# -gt 0 ]; do
  case "$1" in
    --full) FULL=1; shift ;;
    --serial|-s) SERIAL="$2"; shift 2 ;;
    -*) echo "Unknown arg: $1" >&2; exit 2 ;;
    *) SERIAL="$1"; shift ;;
  esac
done
if [ -n "$SERIAL" ]; then "$ADB" connect "$SERIAL" >/dev/null 2>&1 || true; fi

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
[ -n "$SERIAL" ] || { echo "ERROR: No Tbox ADB device reachable: pass --serial <box-ip>:5555 or set TBOX_ADB_IPS in .env." >&2; exit 1; }

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
  echo carlife=\$(getprop persist.tbox.carlife) media=\$(getprop persist.tbox.carlife.audio.media) navi=\$(getprop persist.tbox.carlife.audio.navi) call=\$(getprop persist.tbox.carlife.audio.call) aec=\$(getprop persist.tbox.carlife.aec) aec_dump=\$(getprop persist.tbox.carlife.aec.dump) btsnoop=\$(getprop persist.bluetooth.btsnoopenable)
  echo ===BT_PRIORITIES===
  settings list global | grep -E \"bluetooth_(a2dp_sink|headset)_priority_\"
  echo ===STATUS===
  cat /cache/carlife/status 2>/dev/null || true
  echo ===PS===
  ps -ef | grep -E \"carlife-daemon|org\\.carlife\\.hulab|sdsdk816\" | grep -v grep
  echo ===TOMBSTONES===
  ls -la /data/tombstones/ 2>/dev/null || true
  echo ===X7_LOG===
  ls -lt /storage/emulated/0/x7_Log/ 2>/dev/null | head -n 15 || true
'" > "$OUT/device_state.txt"

# Bluetooth HCI snoop (persist.bluetooth.btsnoopenable=true) and echo-canceller PCM dumps.
mkdir -p "$OUT/bt" "$OUT/aec"
for f in $("$ADB" -s "$SERIAL" shell "su -c 'ls /data/misc/bluetooth/logs/ 2>/dev/null'" | tr -d '\r'); do
  [ -n "$f" ] || continue
  "$ADB" -s "$SERIAL" exec-out "su -c 'cat /data/misc/bluetooth/logs/$f'" > "$OUT/bt/$f"
done
for d in $("$ADB" -s "$SERIAL" shell "su -c 'ls /cache/carlife/aec/ 2>/dev/null'" | tr -d '\r'); do
  [ -n "$d" ] || continue
  mkdir -p "$OUT/aec/$d"
  for f in mic_raw.pcm ref_aligned.pcm aec_out.pcm; do
    "$ADB" -s "$SERIAL" exec-out "su -c 'cat /cache/carlife/aec/$d/$f 2>/dev/null'" > "$OUT/aec/$d/$f"
  done
done

if [ "$FULL" = "1" ]; then
  LATEST_AND=$("$ADB" -s "$SERIAL" shell "ls -t /storage/emulated/0/x7_Log/and_*.ini 2>/dev/null | head -n 1" | tr -d '\r')
  LATEST_KERN=$("$ADB" -s "$SERIAL" shell "ls -t /storage/emulated/0/x7_Log/kernel_*.ini 2>/dev/null | head -n 1" | tr -d '\r')
  [ -n "$LATEST_AND" ] && "$ADB" -s "$SERIAL" pull "$LATEST_AND" "$OUT/$(basename "$LATEST_AND")"
  [ -n "$LATEST_KERN" ] && "$ADB" -s "$SERIAL" pull "$LATEST_KERN" "$OUT/$(basename "$LATEST_KERN")"
fi

ls -lh "$OUT"
