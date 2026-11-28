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

WORK_DIR="$(mktemp -d /tmp/omit-build-XXXXXX)"
trap 'rm -rf "$WORK_DIR"' EXIT

# --- Guards: never ship a stale UI bundle or a placeholder rule table ---
UI_INDEX="$REPO_ROOT/app/src/main/assets/ui/index.html"
if [ ! -f "$UI_INDEX" ]; then
  echo "ERROR: app/src/main/assets/ui/index.html is missing. Run 'npm run build' in ui-web/ first." >&2
  exit 1
fi
if ! grep -q 'assets/index-' "$UI_INDEX"; then
  echo "ERROR: the UI bundle in assets/ui looks unbundled. Re-run the Vite build." >&2
  exit 1
fi
VBIN="$REPO_ROOT/app/src/main/assets/omit_starter.vbin"
VBIN_RULES=$(python3 - "$VBIN" <<'EOF'
import struct, sys
with open(sys.argv[1], "rb") as f:
    header = f.read(16)
if len(header) < 16 or header[:8] != b"VELLUM01":
    print(0)
else:
    print(struct.unpack(">i", header[8:12])[0])
EOF
)
echo "      Rule table: ${VBIN_RULES} domains"
if [ "${VBIN_RULES:-0}" -lt 1000 ]; then
  echo "WARNING: only ${VBIN_RULES} starter rules are present. Run scripts/compile_blocklists.py to ship the full table." >&2
fi

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

# Signing identity must be stable forever: Android refuses to install an update
# whose certificate differs from the installed app's. A keystore therefore has to
# come from CI secrets (decoded to signing/omit-release.jks) or be supplied via
# SIGNING_KEYSTORE. Generating a fresh key here would silently break every
# existing install, so we only do it for explicit local/unsigned builds.
KEYSTORE="${SIGNING_KEYSTORE:-$REPO_ROOT/signing/omit-release.jks}"
KS_PASS="${SIGNING_STORE_PASS:-}"
KEY_PASS="${SIGNING_KEY_PASS:-$KS_PASS}"
KEY_ALIAS="${SIGNING_KEY_ALIAS:-omit}"

if [ ! -f "$KEYSTORE" ] && [ -n "${SIGNING_KEYSTORE_BASE64:-}" ]; then
  # CI hands the keystore over base64-encoded so it never lands in the repo.
  mkdir -p "$(dirname "$KEYSTORE")"
  printf '%s' "$SIGNING_KEYSTORE_BASE64" | base64 -d > "$KEYSTORE"
  echo "      Keystore restored from SIGNING_KEYSTORE_BASE64."
fi

if [ ! -f "$KEYSTORE" ]; then
  if [ "${ALLOW_EPHEMERAL_KEY:-0}" = "1" ]; then
    echo "      WARNING: no keystore found; generating a throwaway key."
    echo "      WARNING: installs signed with it will NOT update in place." >&2
    KEYSTORE="$WORK_DIR/omit-ephemeral.jks"
    KS_PASS="${KS_PASS:-omit2026}"
    KEY_PASS="${KEY_PASS:-omit2026}"
    "$KEYTOOL" -genkeypair \
      -keystore "$KEYSTORE" \
      -storepass "$KS_PASS" \
      -keypass "$KEY_PASS" \
      -alias "$KEY_ALIAS" \
      -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=Omit Shield, OU=Mobile, O=Omit, L=Jaipur, ST=RJ, C=IN" >/dev/null 2>&1
  else
    echo "ERROR: release keystore missing at $KEYSTORE." >&2
    echo "       Provide SIGNING_KEYSTORE + SIGNING_STORE_PASS, or set ALLOW_EPHEMERAL_KEY=1" >&2
    echo "       for a local, non-updatable build." >&2
    exit 1
  fi
fi

# Fingerprint goes into the log so the identity of every release is auditable.
"$KEYTOOL" -list -v -keystore "$KEYSTORE" -storepass "$KS_PASS" -alias "$KEY_ALIAS" 2>/dev/null \
  | grep -E "SHA256:|Subject:" | sed 's/^/      /' || true

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
