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
JAVAC="${JAVAC_BIN:-$(command -v javac || echo $HOME/.sdkman/candidates/java/current/bin/javac)}"
KEYSTORE="${TBOX_KEYSTORE:-$REPO/.keystore/usbswitch-release.jks}"

APP="$DIR/app"
BUILD="$DIR/build"
OUT_APK="$BUILD/TboxUsbSwitch.apk"

rm -rf "$BUILD"
mkdir -p "$BUILD/res-compiled" "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"

"$BT/aapt2" compile --dir "$APP/res" -o "$BUILD/res-compiled/res.zip"
"$BT/aapt2" link \
  -o "$BUILD/unsigned-unaligned.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$APP/AndroidManifest.xml" \
  --java "$BUILD/gen" \
  --min-sdk-version 24 \
  --target-sdk-version 28 \
  "$BUILD/res-compiled/res.zip"

"$JAVAC" \
  -source 1.8 -target 1.8 \
  -encoding UTF-8 \
  -classpath "$ANDROID_JAR" \
  -d "$BUILD/classes" \
  "$BUILD/gen/com/tbox/usbswitch/R.java" \
  "$APP/src/com/tbox/usbswitch/MainActivity.java"

"$BT/d8" \
  --min-api 24 \
  --lib "$ANDROID_JAR" \
  --output "$BUILD/dex" \
  $(find "$BUILD/classes" -name "*.class")

python3 - "$BUILD/unsigned-unaligned.apk" "$BUILD/dex/classes.dex" <<'PY'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, "a", compression=zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
PY

"$BT/zipalign" -f -p 4 "$BUILD/unsigned-unaligned.apk" "$BUILD/unsigned-aligned.apk"

mkdir -p "$(dirname "$KEYSTORE")"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -v \
    -keystore "$KEYSTORE" \
    -storepass tboxusbswitch \
    -keypass tboxusbswitch \
    -alias usbswitch \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=TboxUsbSwitch, O=Local, C=CN"
fi

"$BT/apksigner" sign \
  --ks "$KEYSTORE" \
  --ks-key-alias usbswitch \
  --ks-pass pass:tboxusbswitch \
  --key-pass pass:tboxusbswitch \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --out "$OUT_APK" \
  "$BUILD/unsigned-aligned.apk"

"$BT/apksigner" verify --verbose "$OUT_APK" >/dev/null
ls -lh "$OUT_APK"
if command -v sha256sum >/dev/null 2>&1; then sha256sum "$OUT_APK"; else shasum -a 256 "$OUT_APK"; fi
