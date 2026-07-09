# Releasing Arya (آریا)

Package: `io.agents.arya`  
Repo: https://github.com/asmoia/arya-agent

## Why signing matters

Android only allows **in-place upgrade** (no uninstall) when the new APK is signed with the **same certificate** as the installed one.

- Debug APKs from CI use the debug key → fine for testing, **not** a long-term upgrade path across machines.
- Production path: one stable **release keystore** stored in GitHub Secrets.

## Required GitHub Secrets (Settings → Secrets and variables → Actions)

| Secret | Content |
|---|---|
| `ANDROID_KEYSTORE_B64` | `base64 -w 0 arya-release.keystore` |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_ALIAS` | key alias |
| `ANDROID_KEY_PASSWORD` | key password |

Generate once:

```bash
keytool -genkeypair -v -keystore arya-release.keystore -alias arya-release \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w 0 arya-release.keystore
```

Keep the keystore offline forever. Losing it = users must uninstall to switch keys.

## Publish a release

1. Bump `versionCode` / `versionName` in `app/build.gradle.kts`
2. Update `CHANGES.md`
3. Commit + push `main`
4. Tag and push:

```bash
git tag -a v0.2.0 -m "v0.2.0"
git push origin v0.2.0
```

5. Workflow `.github/workflows/release.yml`:
   - **With secrets** → signed release APK on GitHub Releases
   - **Without secrets** → debug APK as **prerelease** (still uploaded so Releases are never empty)

## Local models across upgrades

Models under `Android/data/io.agents.arya/files/models/` survive APK updates.  
Only **Clear Data** / uninstall removes them. Prefer Hermes Export before destructive resets.

## Update checker

App polls `https://api.github.com/repos/asmoia/arya-agent/releases/latest` once per day.
