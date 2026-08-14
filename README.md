<div align="center">
  <h1>FawnTavern</h1>

  <p><strong>简体中文</strong> | <a href="./README.en.md">English</a></p>

  <p>
    <a href="https://developer.android.com/"><img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat&amp;logo=android&amp;logoColor=white" alt="Android 8.0+" /></a>
    <a href="https://openjdk.org/"><img src="https://img.shields.io/badge/JDK-17-ED8B00?style=flat&amp;logo=openjdk&amp;logoColor=white" alt="JDK 17" /></a>
  </p>
</div>

FawnTavern 是一款轻量 AI 角色扮演聊天的 Android 客户端，支持角色卡、世界书、预设、多提供商流式聊天、联网搜索、语音朗读、附件以及本地备份与恢复。

## 开发环境

需要 JDK 17，以及兼容 `compileSdk 37` 的 Android SDK。

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
```

Pull Request 必须通过 GitHub Actions 验证。工作流会执行 JVM 单元测试、Android lint、Debug/Release 构建、Room Schema 校验，以及 Android 模拟器上的 Compose UI 测试。

## 数据兼容

聊天记录使用 Room 存储。每次修改数据库结构时必须：

1. 在 `ChatDatabase` 中添加明确的 `Migration`。
2. 增加从上一版本升级并保留代表性数据的测试。
3. 提交 `app/schemas` 中生成的 JSON Schema。

发布版本禁止使用破坏性数据库迁移。备份文件带有格式版本，并须保持对已支持旧版本的向后兼容。

## 安全策略

API 提供商、搜索和 TTS 凭据使用 Android Keystore 中的 AES-GCM 密钥加密。Android 系统备份会排除凭据、聊天数据库和附件。

远程 HTTP 地址默认被阻止，自定义服务必须使用 HTTPS。未加密 HTTP 只允许访问 `localhost`、`127.0.0.1` 和 Android 模拟器宿主机 `10.0.2.2`。

## 发布构建

推送 `v0.2.0` 形式的标签会触发 `.github/workflows/release.yml`，生成签名 APK、AAB 和校验文件，同时创建公开的 GitHub Release。应用通过 GitHub Releases 检查正式版更新。

推送 `v0.2.0-beta.1` 形式的标签会触发 `.github/workflows/beta.yml`，运行相同的签名构建并创建带 APK、AAB 和 SHA-256 校验文件的 GitHub Pre-release。

发布标签必须指向 `main` 分支中已通过 Android CI 的提交。标签工作流会复用该验证结果，仅执行一次签名构建，不再重复运行测试和 Lint。

首次发布前，需要配置以下仓库 Secrets：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`
- `GOOGLE_SERVICES_JSON`（Firebase 控制台下载文件的完整内容）

`versionName` 和递增的 Android `versionCode` 均由 Git 标签自动生成，无需维护仓库版本变量。

## 发布检查清单

- 发布提交的 CI 全部通过。
- 持久化数据变更具备升级及备份恢复测试。
- 用户可见的状态变化具备 UI 回归测试。
- Git 标签符合 `vX.Y.Z` 或 `vX.Y.Z-beta.N` 格式。
- 发布说明已经记录用户可见变化及兼容性影响。
- beta 安装包已经通过设置中的“崩溃报告”页面验证本地报告，以及用户授权后的 Firebase 上报流程。
