# Releasing Arya

## Signed Release Builds

Arya uses a release keystore stored as GitHub Actions secrets. When all four secrets are present, the `release.yml` workflow produces a **signed release APK** that can be installed over previous versions without uninstalling.

### Required GitHub Secrets

| Secret | Description |
|--------|-------------|
| `ANDROID_KEYSTORE_B64` | Base64-encoded `.keystore` file |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Key alias inside the keystore |
| `ANDROID_KEY_PASSWORD` | Key password (usually same as keystore password) |

### How to Generate a New Keystore

```bash
keytool -genkeypair -v \
  -keystore arya-release.keystore \
  -alias arya \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storepass 'YOUR_PASSWORD' \
  -keypass 'YOUR_PASSWORD' \
  -dname "CN=Arya Agent, OU=AI, O=asmoia, L=Istanbul, C=TR"

base64 -w0 arya-release.keystore > arya-release.keystore.b64
```

Then set the four GitHub secrets via repo Settings → Secrets → Actions.

### Creating a Release

```bash
# Bump version in app/build.gradle.kts (versionCode + versionName)
git commit -am "release: v0.6.1"
git tag v0.6.1
git push origin main --tags
```

The `release.yml` workflow will:
1. Detect signing secrets → build signed release APK
2. If secrets missing → fall back to debug unsigned APK (prerelease)

### Important

- **Never lose the keystore file!** If lost, you cannot publish updates that install over the old app.
- **Never commit the keystore to git.** It's in `.gitignore`.
- The CI workflow writes `local.properties` at build time — the keystore only exists in the CI runner's temp directory.
