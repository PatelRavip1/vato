#!/usr/bin/env bash
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

SDK="${ANDROID_HOME:-$HOME/.android/sdk}"
BUILD_TOOLS="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin}"
if [ -z "$JAVA_BIN" ] && [ -d "$HOME/.jdk21/bin" ]; then
  JAVA_BIN="$HOME/.jdk21/bin"
fi
JAVAC="${JAVA_BIN:+$JAVA_BIN/}javac"
KEYTOOL="${JAVA_BIN:+$JAVA_BIN/}keytool"
if ! command -v "$JAVAC" >/dev/null 2>&1; then JAVAC="javac"; fi
if ! command -v "$KEYTOOL" >/dev/null 2>&1; then KEYTOOL="keytool"; fi

if [ ! -f "debug.keystore" ]; then
  echo "[*] Generating debug keystore..."
  "$KEYTOOL" -genkeypair -v \
    -keystore debug.keystore \
    -storepass android \
    -alias androiddebugkey \
    -keypass android \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US"
fi

rm -rf build compiled_res.zip base.apk aligned.apk classes.dex obj
mkdir -p build obj

echo "[1/6] Compiling resources..."
"$BUILD_TOOLS/aapt2" compile --dir res -o compiled_res.zip

echo "[2/6] Linking package..."
"$BUILD_TOOLS/aapt2" link -o base.apk \
  -I "$PLATFORM" \
  --manifest AndroidManifest.xml \
  -A assets \
  compiled_res.zip \
  --auto-add-overlay

echo "[3/6] Compiling Java..."
"$JAVAC" --release 8 -cp "$PLATFORM" -d obj src/com/vato/qwenchat/MainActivity.java

echo "[4/6] Converting to DEX..."
"$BUILD_TOOLS/d8" --lib "$PLATFORM" --output . obj/com/vato/qwenchat/*.class

echo "[5/6] Packaging DEX..."
zip -q -u base.apk classes.dex

echo "[6/6] Aligning & Signing APK..."
"$BUILD_TOOLS/zipalign" -f -p 4 base.apk aligned.apk
"$BUILD_TOOLS/apksigner" sign \
  --ks debug.keystore \
  --ks-pass pass:android \
  --ks-key-alias androiddebugkey \
  --key-pass pass:android \
  --out vato.apk \
  aligned.apk

if [ -n "${OUTPUT_DIR:-}" ] && [ -d "$OUTPUT_DIR" ]; then
  cp -f vato.apk "$OUTPUT_DIR/vato.apk"
fi
echo "[+] APK ready: $DIR/vato.apk ($(du -h vato.apk | cut -f1))"
