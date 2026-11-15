#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_APK="${1:-$REPO_ROOT/dist-release/Omit.apk}"

export JAVA_HOME="${JAVA_HOME:-/opt/jdk-17}"
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
export KOTLIN_HOME="${KOTLIN_HOME:-/opt/kotlin}"

AAPT2="$ANDROID_SDK_ROOT/build-tools/34.0.0/aapt2"
D8="$ANDROID_SDK_ROOT/build-tools/34.0.0/d8"
ZIPALIGN="$ANDROID_SDK_ROOT/build-tools/34.0.0/zipalign"
APKSIGNER="$ANDROID_SDK_ROOT/build-tools/34.0.0/apksigner"
ANDROID_JAR="$ANDROID_SDK_ROOT/platforms/android-34/android.jar"
KOTLINC="$KOTLIN_HOME/bin/kotlinc"
KEYTOOL="$JAVA_HOME/bin/keytool"

WORK_DIR="$(mktemp -d /tmp/vellum-build-XXXXXX)"
trap 'rm -rf "$WORK_DIR"' EXIT

echo "[1/5] Compiling Android resources with aapt2..."
mkdir -p "$WORK_DIR/res_compiled" "$WORK_DIR/gen" "$WORK_DIR/classes" "$WORK_DIR/dex"
"$AAPT2" compile --dir "$REPO_ROOT/app/src/main/res" -o "$WORK_DIR/res_compiled/res.zip"

"$AAPT2" link \
  -I "$ANDROID_JAR" \
  --manifest "$REPO_ROOT/app/src/main/AndroidManifest.xml" \
  -A "$REPO_ROOT/app/src/main/assets" \
  -0 .vbin -0 .woff2 \
  --java "$WORK_DIR/gen" \
  --auto-add-overlay \
  -o "$WORK_DIR/unsigned_unaligned.apk" \
  "$WORK_DIR/res_compiled/res.zip"

echo "[2/5] Compiling Java R.java + Kotlin engine & WebView bridge..."
"$JAVA_HOME/bin/javac" -source 17 -target 17 \
  -cp "$ANDROID_JAR" \
  -d "$WORK_DIR/classes" \
  $(find "$WORK_DIR/gen" -name "*.java")

"$KOTLINC" \
  -jvm-target 17 \
  -cp "$ANDROID_JAR:$WORK_DIR/classes" \
  -d "$WORK_DIR/classes" \
  $(find "$REPO_ROOT/app/src/main/java" -name "*.kt")

echo "[3/5] Dexing bytecode with R8/D8..."
CLASS_JAR="$WORK_DIR/app_classes.jar"
"$JAVA_HOME/bin/jar" cf "$CLASS_JAR" -C "$WORK_DIR/classes" .

"$D8" \
  --release \
  --min-api 26 \
  --lib "$ANDROID_JAR" \
  --output "$WORK_DIR/dex" \
  "$CLASS_JAR" \
  "$KOTLIN_HOME/lib/kotlin-stdlib.jar"

echo "[4/5] Packaging & 4-byte aligning APK..."
(cd "$WORK_DIR/dex" && zip -q -0 "$WORK_DIR/unsigned_unaligned.apk" classes*.dex)
"$ZIPALIGN" -f -p 4 "$WORK_DIR/unsigned_unaligned.apk" "$WORK_DIR/unsigned_aligned.apk"

echo "[5/5] Signing APK (v1 + v2 + v3 schemes)..."
KEYSTORE="${SIGNING_KEYSTORE:-$WORK_DIR/vellum-release.jks}"
KS_PASS="${SIGNING_STORE_PASS:-vellum2026}"
KEY_ALIAS="${SIGNING_KEY_ALIAS:-vellum}"
KEY_PASS="${SIGNING_KEY_PASS:-vellum2026}"

if [ ! -f "$KEYSTORE" ]; then
  "$KEYTOOL" -genkeypair \
    -keystore "$KEYSTORE" \
    -storepass "$KS_PASS" \
    -keypass "$KEY_PASS" \
    -alias "$KEY_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Vellum Shield, OU=Mobile, O=Vellum, L=Jaipur, ST=RJ, C=IN" >/dev/null 2>&1
fi

mkdir -p "$(dirname "$OUT_APK")"
"$APKSIGNER" sign \
  --ks "$KEYSTORE" \
  --ks-pass "pass:$KS_PASS" \
  --ks-key-alias "$KEY_ALIAS" \
  --key-pass "pass:$KEY_PASS" \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --out "$OUT_APK" \
  "$WORK_DIR/unsigned_aligned.apk"

"$APKSIGNER" verify --verbose "$OUT_APK" >/dev/null
echo "Built & verified: $OUT_APK ($(du -h "$OUT_APK" | cut -f1))"
