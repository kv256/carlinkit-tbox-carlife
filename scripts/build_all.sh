#!/usr/bin/env bash
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"

echo "=== [1/4] Build carlife-md-api28.jar ==="
bash "$REPO/carlife-md-api28/build.sh"

echo "=== [2/4] Build TboxBtPhoneOverlay.apk ==="
bash "$REPO/bt-phone-overlay/build.sh"

echo "=== [3/4] Build TboxUsbSwitch.apk ==="
bash "$REPO/tbox-usb-switch/build.sh"

echo "=== [4/4] Build TboxCarLauncher.apk ==="
bash "$REPO/tbox-car-launcher/build.sh"

echo "=== All artifacts built cleanly ==="
