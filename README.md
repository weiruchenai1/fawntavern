<div align="center">
  <h1>FawnTavern</h1>

  <p><strong>简体中文</strong> | <a href="./README.en.md">English</a></p>

  <p>
    <a href="https://developer.android.com/"><img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat&amp;logo=android&amp;logoColor=white" alt="Android 8.0+" /></a>
    <a href="https://openjdk.org/"><img src="https://img.shields.io/badge/JDK-17-ED8B00?style=flat&amp;logo=openjdk&amp;logoColor=white" alt="JDK 17" /></a>
    <a href="https://github.com/weiruchenai1/fawntavern"><img src="https://img.shields.io/github/languages/top/weiruchenai1/fawntavern?style=flat&amp;logo=kotlin&amp;color=7F52FF" alt="Top Language" /></a>
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

推送 `v0.2.0` 形式的标签会触发 `.github/workflows/release.yml`，生成签名 APK 和 AAB，并将它们保存为私有 GitHub Actions 构建产物。

首次发布前，需要配置以下仓库 Secrets：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

每次发布前，将仓库变量 `ANDROID_VERSION_CODE` 更新为严格递增的整数。`versionName` 取自 Git 标签。

## 发布检查清单

- 发布提交的 CI 全部通过。
- 持久化数据变更具备升级及备份恢复测试。
- 用户可见的状态变化具备 UI 回归测试。
- `ANDROID_VERSION_CODE` 已递增。
- 发布说明已经记录用户可见变化及兼容性影响。
