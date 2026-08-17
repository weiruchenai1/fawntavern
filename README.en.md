<div align="center">
  <img src="./logo.svg" width="96" alt="FawnTavern" />
  <h1>FawnTavern</h1>
  <p>A lightweight, modern AI role-playing chat client for Android</p>

  <p><a href="./README.md">简体中文</a> | <strong>English</strong></p>

  <p>
    <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat&amp;logo=android&amp;logoColor=white" alt="Android 8.0+" />
    <img src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?style=flat&amp;logo=kotlin&amp;logoColor=white" alt="Kotlin + Jetpack Compose" />
  </p>
</div>

FawnTavern is a lightweight AI role-playing chat client. It imports SillyTavern-compatible character cards, presets, and world books, connects to multiple LLM APIs, and lets you chat with your favorite characters anytime, anywhere.

## Screenshots

<div align="center">
  <img src="./screenshots/1.jpg" width="240" alt="Screenshot 1" />
  <img src="./screenshots/2.jpg" width="240" alt="Screenshot 2" />
  <img src="./screenshots/3.jpg" width="240" alt="Screenshot 3" />
</div>

## Features

- Import and manage SillyTavern-compatible character cards, presets, and world books
- Connect to multiple model providers and OpenAI-compatible APIs
- Stream responses, edit messages, regenerate replies, and switch response versions
- Use web search, text-to-speech, image attachments, and file attachments
- Configure prompts, quick replies, and chat interface preferences
- Keep conversations, characters, and settings on the device
- Back up and restore local data and check for updates in the app

## Download

Download the latest version from [GitHub Releases](https://github.com/weiruchenai1/fawntavern/releases).

| Package | Intended devices |
| --- | --- |
| `FawnTavern-<version>-arm64-v8a.apk` | Most modern Android phones and tablets |
| `FawnTavern-<version>-x86_64.apk` | x86_64 Android emulators and some Chromebooks |

Most phones should use `arm64-v8a`. FawnTavern does not publish packages through third-party download sites. Verify downloads against the SHA-256 checksum file on the Release page.

## Requirements

- Android 8.0 (API 26) or newer
- An `arm64-v8a` or `x86_64` device
- A network connection for online models, web search, and update checks
- Your own API service and quota; FawnTavern does not include model usage credits

## Privacy and data

- Conversations, character cards, presets, world books, and attachments are stored locally by default.
- API, search, and TTS credentials are encrypted with an AES-GCM key protected by Android Keystore.
- Model requests are sent to the API provider you configure. Review that provider's privacy policy as well.

## Disclaimer

This application is intended for learning and entertainment. AI-generated content does not represent the developer's views. Follow the terms of each model, search, or speech provider and the laws applicable in your location.

## Development

Development requires JDK 17 and an Android SDK that supports `compileSdk 37`.

```powershell
git clone https://github.com/weiruchenai1/fawntavern.git
cd fawntavern
.\gradlew.bat assembleDebug
```

## License

This project is licensed under [AGPL-3.0](./LICENSE).

## Acknowledgements

- [SillyTavern](https://github.com/SillyTavern/SillyTavern)

This project also references other open-source projects. Special thanks to:

- [RikkaHub](https://github.com/rikkahub/rikkahub)
- [Kelivo](https://github.com/Chevey339/kelivo)
