#!/usr/bin/env bash
# Creates the RELEASE signing key for Cigram and the git-ignored keystore.properties that app/build.gradle.kts reads.
#
#   tools/make-release-keystore.sh [keystore-file] [alias]
#
# * 4096-bit RSA, valid ~27 years, random 32-char passwords (printed ONCE, also stored in keystore.properties, chmod 600).
# * Refuses to overwrite an existing keystore: losing or replacing this key means nobody can update the app any more.
# * Back the .jks AND keystore.properties up somewhere private (password manager / encrypted drive). NEVER commit them
#   (.gitignore already excludes them).
set -euo pipefail

STORE="${1:-cigram-release.jks}"
ALIAS="${2:-cigram-release}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROPS="$ROOT/keystore.properties"

command -v keytool >/dev/null || { echo "keytool not found (install a JDK 17+)" >&2; exit 1; }
[ ! -e "$STORE" ] || { echo "Refusing to overwrite existing $STORE" >&2; exit 1; }
[ ! -e "$PROPS" ] || { echo "Refusing to overwrite existing $PROPS" >&2; exit 1; }

# fixed-size read first, so no pipe is closed early (an early-closed pipe is a SIGPIPE = exit 141 under `pipefail`)
rand() { head -c 96 /dev/urandom | base64 -w0 | tr -dc 'A-Za-z0-9' | cut -c1-32; }
STORE_PASS="$(rand)"
KEY_PASS="$STORE_PASS"   # PKCS12 keystores use one password for store and key

umask 077
keytool -genkeypair -v \
  -keystore "$STORE" -storetype PKCS12 \
  -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$STORE_PASS" -keypass "$KEY_PASS" \
  -dname "CN=Cigram, OU=Release, O=Cigram, C=US" >/dev/null

STORE_ABS="$(cd "$(dirname "$STORE")" && pwd)/$(basename "$STORE")"
cat > "$PROPS" <<EOF
storeFile=$STORE_ABS
storePassword=$STORE_PASS
keyAlias=$ALIAS
keyPassword=$KEY_PASS
EOF
chmod 600 "$PROPS" "$STORE_ABS"

echo
echo "Release key created:"
echo "  keystore   : $STORE_ABS"
echo "  properties : $PROPS"
echo "  alias      : $ALIAS"
echo "  password   : $STORE_PASS   <-- save it now"
echo
echo "Certificate fingerprints (publish the SHA-256 on your site so users can verify the app):"
keytool -list -v -keystore "$STORE_ABS" -alias "$ALIAS" -storepass "$STORE_PASS" 2>/dev/null | grep -E "SHA1:|SHA256:"
echo
echo "Next:  gradle :app:assembleDirectRelease   (or push to CI with the 4 KEYSTORE_* secrets)"
