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

These four secrets are already configured on `asmoia/arya-agent` (used for v0.5.x / v0.6.x signed releases). Do **not** rotate the keystore unless you accept that users must uninstall first.

### How to Generate a New Keystore (only if starting over)

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
# Bump versionCode / versionName in app/build.gradle.kts
git commit -am "release: v1.1.0"
git tag v1.1.0
git push origin redesign/v1
git push origin v1.1.0
```

Or run **Actions → Release APK → Run workflow** (`workflow_dispatch`) on the tag.

The `release.yml` workflow will:
1. Detect signing secrets → build signed release APK (`assembleRelease`)
2. If secrets missing → fall back to debug unsigned APK (prerelease)
3. Attach APK + `SHA256SUMS.txt` to a GitHub Release

### Native engine pin

CMake FetchContent pins `ggml-org/llama.cpp` **b10566**. CI installs NDK `27.2.12479018` and CMake `3.22.1`. First compile of llama.cpp is slow (~10–20 min per ABI).

### Important

- **Never lose the keystore file!** If lost, you cannot publish updates that install over the old app.
- **Never commit the keystore to git.** It's in `.gitignore`.
- **Never commit `GH_TOKEN` / PATs.** Use `git push` over HTTPS with a local env var, or `gh auth`.
- The CI workflow writes `local.properties` at build time — the keystore only exists in the CI runner's temp directory.
