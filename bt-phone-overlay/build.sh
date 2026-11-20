#!/usr/bin/env bash
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$DIR/.." && pwd)"
SDK="${ANDROID_SDK_DIR:-$REPO/../tools/android-sdk}"
BT="$SDK/build-tools"
if [ ! -x "$BT/aapt2" ]; then
  # Standard SDK layout: build-tools/<version>/ (pick the newest)
  BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
  BT="${BT%/}"
fi
ANDROID_JAR="$SDK/platforms/android-28/android.jar"
KEYSTORE="${TBOX_KEYSTORE:-$REPO/.keystore/usbswitch-release.jks}"
BUILD="$DIR/build"
OUT_APK="$BUILD/TboxBtPhoneOverlay.apk"

rm -rf "$BUILD"
mkdir -p "$BUILD"

"$BT/aapt2" compile --dir "$DIR/res" -o "$BUILD/res.zip"
"$BT/aapt2" link \
  -o "$BUILD/unsigned.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$DIR/AndroidManifest.xml" \
  --min-sdk-version 28 \
  --target-sdk-version 28 \
  --no-resource-deduping \
  -0 arsc \
  "$BUILD/res.zip"

"$BT/zipalign" -f -p 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

"$BT/apksigner" sign \
  --ks "$KEYSTORE" \
  --ks-key-alias usbswitch \
  --ks-pass pass:tboxusbswitch \
  --key-pass pass:tboxusbswitch \
  --min-sdk-version 24 \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --v3-signing-enabled false \
  --out "$OUT_APK" \
  "$BUILD/aligned.apk"

"$BT/apksigner" verify --verbose "$OUT_APK" >/dev/null
ls -lh "$OUT_APK"
if command -v sha256sum >/dev/null 2>&1; then sha256sum "$OUT_APK"; else shasum -a 256 "$OUT_APK"; fi
