#!/usr/bin/env bash
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$DIR/.." && pwd)"
SDK="${ANDROID_SDK_DIR:-$REPO/../tools/android-sdk}"
BT="$SDK/build-tools"
ANDROID_JAR="$SDK/platforms/android-28/android.jar"
JAVAC="${JAVAC_BIN:-$(command -v javac || echo $HOME/.sdkman/candidates/java/current/bin/javac)}"
KEYSTORE="${TBOX_KEYSTORE:-$REPO/.keystore/usbswitch-release.jks}"

APP="$DIR/app"
BUILD="$DIR/build"
OUT_APK="$BUILD/TboxCarLauncher.apk"

rm -rf "$BUILD"
mkdir -p "$BUILD/res-compiled" "$BUILD/gen" "$BUILD/stubs" "$BUILD/classes" "$BUILD/dex"

# 1. Compile resources
"$BT/aapt2" compile --dir "$APP/res" -o "$BUILD/res-compiled/res.zip"
"$BT/aapt2" link \
  -o "$BUILD/unsigned-unaligned.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$APP/AndroidManifest.xml" \
  --java "$BUILD/gen" \
  --min-sdk-version 28 \
  --target-sdk-version 28 \
  "$BUILD/res-compiled/res.zip"

# 2. Compile hidden-API compile-only stubs (never packaged into DEX)
"$JAVAC" \
  -source 1.8 -target 1.8 \
  -encoding UTF-8 \
  -classpath "$ANDROID_JAR" \
  -d "$BUILD/stubs" \
  $(find "$DIR/compile-stubs" -name "*.java")

# 3. Compile production app sources
"$JAVAC" \
  -source 1.8 -target 1.8 \
  -encoding UTF-8 \
  -classpath "$ANDROID_JAR:$BUILD/stubs" \
  -d "$BUILD/classes" \
  $(find "$BUILD/gen" -name "*.java") \
  $(find "$APP/src" -name "*.java")

# 4. DEX only production classes (excluding stubs)
"$BT/d8" \
  --min-api 28 \
  --release \
  --lib "$ANDROID_JAR" \
  --output "$BUILD/dex" \
  $(find "$BUILD/classes" -name "*.class")

# 5. Package classes.dex into APK
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
  --min-sdk-version 28 \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --out "$OUT_APK" \
  "$BUILD/unsigned-aligned.apk"

"$BT/apksigner" verify --verbose "$OUT_APK" >/dev/null
ls -lh "$OUT_APK"
sha256sum "$OUT_APK"
