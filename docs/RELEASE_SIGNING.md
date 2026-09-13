# Release Signing (Open Chat)

## Why the keystore is sacred
The release keystore IS the app identity. Android updates require the SAME
keystore and alias as the previously installed release. If the keystore is
lost or regenerated, users cannot update — they must uninstall and reinstall,
losing data. Therefore:

1. **Back it up** in at least two places (password manager + offline encrypted
   copy). Backing up is REQUIRED by this project.
2. **Never commit** `release.keystore`, `*.jks`, or `keystore.properties`.
   `.gitignore` already excludes them.
3. **Never regenerate** the key for the same application id — updates would be
   rejected by the signature check.
4. Never print passwords in logs or CI output.

## Local setup
```bash
# one-time keystore creation (keep for the app's lifetime)
keytool -genkeypair -v -keystore release.keystore -alias openchat \
  -keyalg RSA -keysize 2048 -validity 10950 \
  -dname "CN=Open Chat, OU=OpenChat, O=OpenChat, L=Jakarta, C=ID"

cp keystore.properties.example keystore.properties   # fill real values
./build.sh release
```

## CI (GitHub Actions)
Repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 release.keystore` output |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_ALIAS` | `openchat` |
| `ANDROID_KEY_PASSWORD` | key password |
| `AUTOFIX_TOKEN` | (optional) PAT allowing the Auto Fix workflow to push fixes to `main` |
| `AI_API_KEY`, `AI_BASE_URL`, `AI_MODEL` | (optional) AI-assisted auto-fix |

`scripts/prepare_signing.sh` decodes the keystore at build time and exports the
standard `ANDROID_KEYSTORE_PATH/PASSWORD/ALIAS/PASSWORD` environment variables
that `app/build.gradle` consumes. If secrets are absent the workflow STILL
produces installable APKs signed with an explicitly-flagged **ephemeral**
keystore (artifact name warns: it is NOT the persistent release key) — this is
an honest fallback, never presented as a real release signature.

## Update signing requirements
- Every release (tag `v*`) triggers `.github/workflows/release.yml`.
- Output: `OpenChat-arm64-v8a.apk`, `OpenChat-armeabi-v7a.apk`,
  `OpenChat-x86_64.apk` + `checksums.txt` (SHA256), attached to the GitHub Release.
- Same keystore every time — that is the whole point of the secrets setup.

## Rotation / loss
- Compromised key: move users to a new application id (or accept uninstall);
  Android cannot migrate signatures.
- Lost key: same consequence. This is why step 1 is non-negotiable.
