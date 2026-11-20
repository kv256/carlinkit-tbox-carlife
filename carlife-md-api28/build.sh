#!/usr/bin/env bash
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$DIR/.." && pwd)"
SDK="${ANDROID_SDK_DIR:-$REPO/../tools/android-sdk}"
ANDROID_JAR="$SDK/platforms/android-28/android.jar"
D8="$SDK/build-tools/d8"
JAVAC="${JAVAC_BIN:-$(command -v javac || echo $HOME/.sdkman/candidates/java/current/bin/javac)}"

rm -rf "$DIR/build/stubs" "$DIR/build/classes" "$DIR/build/carlife-md-api28.jar"
mkdir -p "$DIR/build/stubs" "$DIR/build/classes"

# 1. Compile hidden-API compile-only stubs (never packaged into DEX)
"$JAVAC" -source 8 -target 8 -encoding UTF-8 -cp "$ANDROID_JAR" -d "$DIR/build/stubs" \
    $(find "$DIR/compile-stubs" -name '*.java')

# 2. Compile production CarLife API 28 MD + UEventWatch
"$JAVAC" -source 8 -target 8 -encoding UTF-8 -cp "$ANDROID_JAR:$DIR/build/stubs" -d "$DIR/build/classes" \
    $(find "$DIR/src" -name '*.java')

# 3. Convert only production classes to Android 9 (min-api 28) DEX JAR
"$D8" --min-api 28 --release \
    --lib "$ANDROID_JAR" \
    --output "$DIR/build/carlife-md-api28.jar" \
    $(find "$DIR/build/classes" -name '*.class')

ls -lh "$DIR/build/carlife-md-api28.jar"
sha256sum "$DIR/build/carlife-md-api28.jar"
