# CLAUDE.md

本文件为 Claude Code (claude.ai/code) 在此仓库中工作时提供指导。

## 项目

ST App — 一个 Android 客户端（Kotlin + Jetpack Compose，Material 3），用于 SillyTavern 风格的 AI 角色扮演聊天。支持导入 SillyTavern 兼容的角色卡、预设和世界书，可对接 OpenAI 兼容 / Gemini / Claude API 进行流式聊天。包名：`me.rerere.stapp`。

## 常用命令

```bash
./gradlew assembleDebug      # 构建 debug APK
./gradlew installDebug       # 构建并安装到已连接的设备/模拟器
./gradlew lint               # Android lint
```

- 需要 JDK 17；minSdk 26、compile/targetSdk 37；Android SDK 路径在 `local.properties` 中。
- 目前没有单元测试或仪器测试（`app/src/test` 和 `app/src/androidTest` 不存在）。
- 依赖版本集中在 `gradle/libs.versions.toml`（AGP 9.x、Kotlin 2.4、Compose BOM）。
- 开发过程中的验证方式是安装到设备/模拟器上实际运行查看。

## 架构

单 Activity 的 Compose 应用，**没有使用导航库**。`MainActivity` 渲染 `ChatScreen`（`ui/chat/ChatScreen.kt`），它既是主聊天界面，也是导航器：文件内有一个私有 `Screen` 枚举和一个 `mutableStateListOf<Screen>` 返回栈，渲染栈顶页面（`when (nav.lastOrNull())`），返回即弹栈。新增页面：加一个枚举值 + 一个 `when` 分支，入口处 `nav.add(Screen.X)`。模型/角色选择等弹层仍用布尔标志 + `ModalBottomSheet`。

**聊天状态在 `ui/chat/ChatViewModel.kt`**（唯一的 ViewModel，`AndroidViewModel` + Compose `mutableStateOf` 状态），它只持有状态、调度协程和落盘；业务逻辑在 `domain/` 层（均为无 Android UI 依赖的普通 Kotlin）：
- `domain/PromptBuilder` — Prompt 拼装，两步走：`build`（角色卡 + 已加载的世界书/预设 → `Built`：历史前后的提示块、深度注入块、发送侧正则、采样参数；世界书条目按 constant/关键词扫描激活，预设按 `promptOrder` 编排、marker 映射角色卡字段，角色卡 `system_prompt`/`post_history_instructions` 优先于预设 main/jailbreak）；`assemble`（每次请求把 `Built` 与聊天历史合成完整 `ApiMessage` 数组：历史逐条套发送侧正则、文件附件内联为 `<file>` 文本块、图片读盘编码 base64、深度注入按位插入）。`{{char}}`/`{{user}}` 宏也在此替换。
- `domain/ConversationOps` — 会话/消息的纯变换（新建会话含开场白、追加消息、alts 多版本切换/删除/编辑、`nextTs`）。输入旧会话返回新会话，不做 IO。**alts 即分支**：每个版本（`MsgAlt`）在 `tail` 里携带自己的下文时间线，切换/删除版本时整条下文换入换出；当前显示版本的 `tail` 恒为空（它的下文就在 `messages` 里）。重答非末条消息前由 `truncateForRegenerate` 把被截断的下文收纳进当前版本。
- `domain/GenerationController` — 流式生成执行器（含 60ms 节流刷新、停止标志；角色卡 `streaming = false` 时不起节流协程，结束时一次性刷新），通过 `onUpdate` 回调发布中间会话，返回最终会话。

生成协程运行在 `viewModelScope`，Activity 重建（深色模式/语言切换）不中断。发送时用户消息立即落盘，生成结束后再存完整会话。注意：同一会话内消息 `ts` 必须严格递增（`ConversationOps.nextTs`），它是消息列表的 LazyColumn key。

**没有依赖注入、没有 Retrofit 之类的封装 —— HTTP 直接用 OkHttp。** 网络层在 `data/api/`：`ChatApi` 是流式聊天入口，接收 `PromptBuilder.assemble` 产出的完整 `ApiMessage` 数组（system/user/assistant，可带 base64 图片）与可选采样参数 `GenParams`（来自关联预设），按 `provider.type` 路由到 `ProviderAdapter` 接口的三个实现（`OpenAiAdapter`/`GoogleAdapter`/`ClaudeAdapter`，各自只做协议编解码；Claude/Gemini 不允许消息数组里出现 system —— 开头连续 system 提升为顶层参数、对话中间的降级为 user，并合并相邻同角色消息，共用工具在 `ApiModels.kt`）；SSE 传输统一在 `SseClient`，OkHttp 客户端全局共享在 `Http`（SSE 用读超时放宽到 5 分钟的派生实例，容忍模型思考期间长时间无数据）。停止生成有两条路径：读循环逐行检查停止标志（快路径）+ 监视协程轮询并 `cancel()` OkHttp Call（覆盖线程阻塞在 `read()` 的情况）。`ModelApi` 负责模型列表/余额查询。新增协议：实现一个 `ProviderAdapter` + 在 `ChatApi.adapterFor` 加一个分支。持久化有两种惯用方式，均为普通的 Kotlin `object`：

- `*Store` 对象 — 设置存储在 `SharedPreferences` 中，使用 `org.json` 手写 JSON 序列化。除 `data/api/ApiConfigStore` 外，其余都在 `data/settings/`（`LanguageStore`、`ThemeStore`（含 `ThemeMode` 枚举）、`FontSizeStore`、`UserProfileStore`、`SearchHistoryStore`）。
- `*Repository` 对象（`data/character`、`data/preset`、`data/worldbook`）— 每个条目对应 `filesDir` 子目录（`characters/`、`worldbooks/`、`presets/`、`regex/`、`avatars/`）下的一个 JSON 文件（`org.json` 手写序列化，保持 SillyTavern 导入/导出兼容），每个领域配有对应的 `*Parser` 和 `*Models` 文件（`regex/` 下是独立导入的 ST 正则脚本，由 `PresetRepository` 管理）。Repository 均为运行在 `Dispatchers.IO` 上的 `suspend` 函数，提供 `load/save/import/delete/rename/clear` 等方法。**UI 不直接操作 Repository 目录下的文件**（例外：`DataManagementScreen` 的导出/统计通过 `dir()` 只读访问目录；用户头像由 `ChatDrawer` 写入 `avatars/`，路径存 `UserProfileStore`）。
- **聊天记录是例外**：`data/chat/ChatRepository` 底层是 Room 数据库（`data/chat/ChatDatabase.kt`，`sessions` + `messages` 两张表，消息主键为 `(sessionId, ts)`，alts/附件列用 kotlinx.serialization 编码；改表结构需要版本号 + `Migration`，不要用破坏性迁移）。对外提供 `sessionsFlow`（Room Flow，写入后自动重发）、`list/save/delete/clear`，以及 `messagesPaged`（Paging 3 分页数据源，UI 目前未接入——现有生成/多版本逻辑需要整个会话在内存里）。消息附件由 `data/chat/AttachmentStore` 在发送时拷入 `attachments/`（图片降采样重编码为 JPEG，content URI 权限是临时的，必须落自有副本），消息里存 filesDir 相对路径；`ChatRepository.clear` 会连带清空该目录。

**SillyTavern 兼容性**是数据层的核心：`CharacterRepository.import` 可以从纯 JSON 文件、或从 PNG 的 `tEXt`/`zTXt` 块（关键字为 `chara`/`ccv3`，base64/zlib 编码）中提取角色 JSON，并自动将内嵌的 `character_book` 提取为独立的世界书文件。导入/导出格式需保持与 SillyTavern 兼容。正则脚本有两个来源：角色卡内嵌（`extensions.regex_scripts` → `CharacterCard.regexScripts`）与独立导入（`regex/` 目录，`RegexScript.toCharRegex()` 转成统一类型），统一由 `data/character/RegexEngine` 套用（对齐 ST 的 JS `/pattern/flags` 字面量与 `$1`/`{{match}}` 替换语法）——**显示侧** `applyForDisplay`（`ChatMessageContent` 按消息深度用 `depthKey` 做 remember 缓存，避免新消息到达时重算旧消息）与**发送侧** `applyForPrompt`（`PromptBuilder.assemble` 构建请求时逐条历史套用）互补：`promptOnly` 只在发送侧生效、`markdownOnly` 只在显示侧生效、两个标志都为 false 的两侧都生效。

**尚未接线的功能**：消息操作栏的翻译按钮只是占位；世界书激活已对齐 ST 的单轮扫描（constant、正则/普通关键词、次级关键词 selectiveLogic、probability 掷骰、条目级 scanDepth/caseSensitive/matchWholeWords 覆盖，默认扫描深度同 ST 为 2 条消息），但**递归激活、minActivations、token 预算、inclusion group、sticky/cooldown 时效**未实现；`ChatRepository.messagesPaged`（Paging 3）未接入 UI。

## 约定

- **国际化**：默认字符串（`values/strings.xml`）为中文；英文在 `values-en/strings.xml`。每个面向用户的字符串都必须同时添加到两处。应用内语言切换由 `data/settings/LanguageStore` + `MainActivity` 中的 `AppCompatDelegate` 处理（切换语言会重启 Activity，并通过 `LanguageStore.consumePendingChange` 重新打开设置页）。
- **图标**：使用 Lucide 图标（`com.composables.icons.lucide`），不要使用 Material 图标 — UI 遵循基于 Lucide 的 Figma 设计稿。
- **Markdown**：消息正文用 mikepenz `multiplatform-markdown-renderer-m3`（纯 Compose）渲染。
- **主题**：浅色/深色模式通过 `data/settings/ThemeStore`（`ThemeMode`）实现，在 `ui/theme/Theme.kt` 中应用；全局字体缩放通过 `data/settings/FontSizeStore` 实现。
