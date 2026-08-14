<div align="center">
  <h1>FawnTavern</h1>

  <p><a href="./README.md">简体中文</a> | <strong>English</strong></p>

  <p>
    <a href="https://developer.android.com/"><img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat&amp;logo=android&amp;logoColor=white" alt="Android 8.0+" /></a>
    <a href="https://openjdk.org/"><img src="https://img.shields.io/badge/JDK-17-ED8B00?style=flat&amp;logo=openjdk&amp;logoColor=white" alt="JDK 17" /></a>
  </p>
</div>

FawnTavern is a lightweight Android client for AI role-playing chat. It supports character cards, world books, presets, streamed multi-provider chat, web search, text-to-speech, attachments, and local backup/restore.

## Development

Requirements: JDK 17 and an Android SDK compatible with `compileSdk 37`.

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
```

Pull requests must pass GitHub Actions verification. The workflow runs JVM unit tests, Android lint, Debug/Release builds, Room schema checks, and Compose UI tests on an Android emulator.

## Data compatibility

Chat history is stored with Room. Every database schema change must:

1. Add an explicit `Migration` in `ChatDatabase`.
2. Add a test that upgrades the preceding schema while preserving representative data.
3. Commit the generated JSON schema in `app/schemas`.

Never add destructive Room migrations for a release build. Backup imports are versioned and must remain backward compatible with supported archive versions.

## Security

Provider, search, and TTS credentials are encrypted with an Android Keystore AES-GCM key. Android system backup excludes credentials, chat databases, and attachments.

Remote HTTP endpoints are blocked. HTTPS is required for custom providers; unencrypted HTTP is permitted only for `localhost`, `127.0.0.1`, and the Android emulator host `10.0.2.2`.

## Release builds

Pushing a tag such as `v0.2.0` starts `.github/workflows/release.yml`, which builds a signed APK and AAB and retains them as private GitHub Actions artifacts.

Pushing a tag such as `v0.2.0-beta.1` starts `.github/workflows/beta.yml`, runs the same signed build, and creates a GitHub prerelease with the APK, AAB, and SHA-256 checksums.

Before the first tagged release, configure these repository secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`
- `GOOGLE_SERVICES_JSON` (the full contents of the file downloaded from Firebase Console)

Set repository variable `ANDROID_VERSION_CODE` to a monotonically increasing integer before each release. The tag supplies `versionName`.

## Release checklist

- CI is green on the release commit.
- Upgrade and backup/restore tests cover changed persistent data.
- UI tests cover changed user-visible state transitions.
- `ANDROID_VERSION_CODE` has been incremented.
- The release notes describe user-facing changes and compatibility impact.
- The beta build has verified local crash reports and the opt-in Firebase reporting flow under Settings > Crash reports.
