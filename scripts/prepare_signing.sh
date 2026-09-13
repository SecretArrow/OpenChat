#!/usr/bin/env bash
# Prepare release signing for CI (spec §19–§20).
# Priority: repo secrets (persistent keystore) > ephemeral generated keystore
# (honest fallback — clearly flagged, never presented as the real release key).
set -euo pipefail

mkdir -p signing

if [ -n "${ANDROID_KEYSTORE_BASE64:-}" ]; then
  echo "$ANDROID_KEYSTORE_BASE64" | base64 -d > signing/release.keystore
  {
    echo "ANDROID_KEYSTORE_PATH=${GITHUB_WORKSPACE}/signing/release.keystore"
    echo "ANDROID_KEYSTORE_PASSWORD=${ANDROID_KEYSTORE_PASSWORD}"
    echo "ANDROID_KEY_ALIAS=${ANDROID_KEY_ALIAS}"
    echo "ANDROID_KEY_PASSWORD=${ANDROID_KEY_PASSWORD}"
  } >> "${GITHUB_ENV}"
  echo "Signing with the PERSISTENT release keystore from repo secrets."
else
  echo "WARNING: ANDROID_KEYSTORE_BASE64 secret is NOT set."
  echo "Generating an EPHEMERAL keystore for this run only."
  echo "APKs signed this way cannot receive updates from a properly signed release."
  echo "Fix: create a keystore once, add ANDROID_KEYSTORE_BASE64,"
  echo "ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD to repo secrets."
  echo "See docs/RELEASE_SIGNING.md."
  KS_PASS="$(python3 -c 'import secrets; print(secrets.token_urlsafe(24))')"
  keytool -genkeypair -v \
    -keystore signing/release.keystore \
    -alias openchat \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=Open Chat, OU=OpenChat, O=OpenChat, L=Jakarta, C=ID" >/dev/null 2>&1
  {
    echo "ANDROID_KEYSTORE_PATH=${GITHUB_WORKSPACE}/signing/release.keystore"
    echo "ANDROID_KEYSTORE_PASSWORD=${KS_PASS}"
    echo "ANDROID_KEY_ALIAS=openchat"
    echo "ANDROID_KEY_PASSWORD=${KS_PASS}"
  } >> "${GITHUB_ENV}"
  echo "ephemeral" > signing/EPHEMERAL
  cat > signing/README-EPHEMERAL.txt <<'EOF'
This keystore was generated inside CI because signing secrets were not configured.
It is NOT the persistent release key. Keep it only to allow installing this one build.
Follow docs/RELEASE_SIGNING.md to set up the persistent key and repo secrets.
EOF
  echo "Ephemeral keystore written to signing/release.keystore (backup artifact will be uploaded)."
fi
