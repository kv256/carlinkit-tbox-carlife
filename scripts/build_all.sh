#!/usr/bin/env bash
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"

echo "=== [1/3] Build carlife-md-api28.jar ==="
bash "$REPO/carlife-md-api28/build.sh"

echo "=== [2/3] Build TboxBtPhoneOverlay.apk ==="
bash "$REPO/bt-phone-overlay/build.sh"

echo "=== [3/3] Build TboxUsbSwitch.apk ==="
bash "$REPO/tbox-usb-switch/build.sh"

echo "=== All artifacts built cleanly ==="
