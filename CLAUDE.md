# CLAUDE.md

本文件为 Claude Code (claude.ai/code) 在此仓库中工作时提供指导。

## 项目

FawnTavern — 一个 Android 客户端（Kotlin + Jetpack Compose，Material 3），用于 SillyTavern 风格的 AI 角色扮演聊天。支持导入 SillyTavern 兼容的角色卡、预设和世界书，可对接 OpenAI 兼容 / Gemini / Claude API 进行流式聊天。包名：`me.rerere.fawntavern`。

## 常用命令

```bash
./gradlew assembleDebug      # 构建 debug APK
./gradlew installDebug       # 构建并安装 debug 版到已连接的设备/模拟器
./gradlew assembleRelease    # 构建 release APK
./gradlew installRelease     # 构建并安装 release 版到已连接的设备/模拟器
./gradlew lint               # Android lint
```

- 需要 JDK 17；minSdk 26、compile/targetSdk 37；Android SDK 路径在 `local.properties` 中。
- 目前没有单元测试或仪器测试（`app/src/test` 和 `app/src/androidTest` 不存在）。
- 依赖版本集中在 `gradle/libs.versions.toml`（AGP 9.x、Kotlin 2.4、Compose BOM）。
- 开发过程中的验证方式是安装到设备/模拟器上实际运行查看。

## 架构

单 Activity 的 Compose 应用，**没有使用导航库**。`MainActivity` 渲染 `ChatScreen`（`ui/chat/ChatScreen.kt`），它既是主聊天界面，也是导航器：文件内有一个私有 `Screen` 枚举和一个 `mutableStateListOf<Screen>` 返回栈，渲染栈顶页面（`when (nav.lastOrNull())`），返回即弹栈。新增页面：加一个枚举值 + 一个 `when` 分支，入口处 `nav.add(Screen.X)`。模型/角色选择等弹层仍用布尔标志 + `ModalBottomSheet`。

**聊天状态在 `ui/chat/ChatViewModel.kt`**（唯一的 ViewModel，`AndroidViewModel` + Compose `mutableStateOf` 状态），它只持有状态、调度协程和落盘；业务逻辑在 `domain/` 层（均为无 Android UI 依赖的普通 Kotlin）：
- `domain/PromptBuilder` — Prompt 拼装，两步走：`build`（角色卡 + 已加载的世界书/预设 → `Built`：历史前后的提示块、深度注入块、发送侧正则、采样参数、token 上限；世界书按多轮激活引擎收集条目，预设按 `promptOrder` 编排、marker 映射角色卡字段，角色卡 `system_prompt`/`post_history_instructions` 优先于预设 main/jailbreak，角色 `extensions.depth_prompt` 作深度注入）；`assemble`（每次请求把 `Built` 与聊天历史合成完整 `ApiMessage` 数组：历史逐条套发送侧正则、文件附件内联为 `<file>` 文本块、图片读盘编码 base64、按 `maxContext − maxTokens` token 预算从新到旧裁剪历史、深度注入按位插入）。宏（`{{char}}`/`{{user}}`/`{{newline}}`/时间日期/`{{random}}`/`{{roll}}`/`{{pick}}`）由 `domain/Macros` 统一替换，`{{original}}` 在角色卡覆盖预设 main/jailbreak 时引用被覆盖原文。世界书激活引擎（`PromptBuilder.activateWorldInfo`）已实现：constant/关键词扫描 + 次级 selectiveLogic + probability 掷骰 + 条目级 scanDepth/caseSensitive/matchWholeWords 覆盖、**递归激活**（excludeRecursion/preventRecursion/delayUntilRecursion）、**inclusion group 互斥组**（groupOverride/groupWeight/useGroupScoring）、**sticky/cooldown/delay 定时效果**（状态存于 `ChatSession.timedWi`，随会话持久化）。
- `domain/ConversationOps` — 会话/消息的纯变换（新建会话含开场白、追加消息、alts 多版本切换/删除/编辑、重答开新版本 `startVariant`、`nextTs`）。输入旧会话返回新会话，不做 IO。**alts 即同位置多版本**（对齐 RikkaHub 的 MessageNode/selectIndex 模型）：每条消息的多个版本（`MsgAlt`）只是不同的内容，`altIdx` 指向当前显示版本，`content` 等镜像字段始终同步 `alts[altIdx]`；本消息之后的时间线由所有版本共享，切换/删除版本、重答任意 assistant 消息（原位开新版本，下文保留）都不改动其后的消息。
- `domain/GenerationController` — 流式生成执行器（含 60ms 节流刷新、停止标志；角色卡 `streaming = false` 时不起节流协程，结束时一次性刷新），通过 `onUpdate` 回调发布中间会话，返回最终会话。

生成协程运行在 `viewModelScope`，Activity 重建（深色模式/语言切换）不中断。发送时用户消息立即落盘，生成结束后再存完整会话。注意：同一会话内消息 `ts` 必须严格递增（`ConversationOps.nextTs`），它是消息列表的 LazyColumn key。

**没有依赖注入、没有 Retrofit 之类的封装 —— HTTP 直接用 OkHttp。** 网络层在 `data/api/`：`ChatApi` 是流式聊天入口，接收 `PromptBuilder.assemble` 产出的完整 `ApiMessage` 数组（system/user/assistant，可带 base64 图片）与可选采样参数 `GenParams`（来自关联预设），按 `provider.type` 路由到 `ProviderAdapter` 接口的三个实现（`OpenAiAdapter`/`GoogleAdapter`/`ClaudeAdapter`，各自只做协议编解码；Claude/Gemini 不允许消息数组里出现 system —— 开头连续 system 提升为顶层参数、对话中间的降级为 user，并合并相邻同角色消息，共用工具在 `ApiModels.kt`）；SSE 传输统一在 `SseClient`，OkHttp 客户端全局共享在 `Http`（SSE 用读超时放宽到 5 分钟的派生实例，容忍模型思考期间长时间无数据）。停止生成有两条路径：读循环逐行检查停止标志（快路径）+ 监视协程轮询并 `cancel()` OkHttp Call（覆盖线程阻塞在 `read()` 的情况）。`ModelApi` 负责模型列表/余额查询。新增协议：实现一个 `ProviderAdapter` + 在 `ChatApi.adapterFor` 加一个分支。持久化有两种惯用方式，均为普通的 Kotlin `object`：

- `*Store` 对象 — 设置存储在 `SharedPreferences` 中，使用 `org.json` 手写 JSON 序列化。除 `data/api/ApiConfigStore` 外，其余都在 `data/settings/`（`LanguageStore`、`ThemeStore`（含 `ThemeMode` 枚举）、`FontSizeStore`、`UserProfileStore`、`SearchHistoryStore`）。
- `*Repository` 对象（`data/character`、`data/preset`、`data/worldbook`）— 每个条目对应 `filesDir` 子目录（`characters/`、`worldbooks/`、`presets/`、`avatars/`）下的一个 JSON 文件（`org.json` 手写序列化，保持 SillyTavern 导入/导出兼容），每个领域配有对应的 `*Parser` 和 `*Models` 文件。Repository 均为运行在 `Dispatchers.IO` 上的 `suspend` 函数，提供 `load/save/import/delete/rename/clear` 等方法；目录/文件级共用操作（`dir/file/listNames/delete/rename/clear`、SAF 文件名查询）抽在 `data/JsonFileDir`，Repository 只保留领域逻辑（解析、ST 兼容序列化、PNG 块处理等）。**UI 不直接操作 Repository 目录下的文件**（例外：`DataManagementScreen` 的导出/统计通过 `dir()` 只读访问目录；用户头像由 `ChatDrawer` 写入 `avatars/`，路径存 `UserProfileStore`）。
- **聊天记录是例外**：`data/chat/ChatRepository` 底层是 Room 数据库（`data/chat/ChatDatabase.kt`，`sessions` + `messages` 两张表，消息主键为 `(sessionId, ts)`，alts/附件列用 kotlinx.serialization 编码；改表结构需要版本号 + `Migration`，不要用破坏性迁移）。对外提供 `sessionsFlow`（Room Flow，写入后自动重发）、`list/save/delete/clear`，以及 `messagesPaged`（Paging 3 分页数据源，**已接入聊天界面**）。**消息列表按 Paging 3 渲染（DB 为单一事实源）**：`ChatViewModel.pagedMessages`（`snapshotFlow{session.id}.flatMapLatest` 按会话切换、`initialKey=count−pageSize` 让最新一页先加载天然停底、`cachedIn` 跨配置变更存活）→ `ChatScreen` 用 `collectAsLazyPagingItems` 渲染。**所有消息修改统一为按 `ts` 的单条 DB 操作**（`switchAlt`/`deleteMessage`/`editMessage`/`putMessage`/`truncateAfter`，单条变换在 `ConversationOps.switchAltOne/deleteAltOne/startVariantOne`，alts 模型保证只改一行），写库后 Room 使分页失效自动重刷，VM 再 `resyncSession` 把内存会话同步回 DB；不再区分消息在内存窗口还是分页里。**流式生成走内存 overlay 旁路**：`GenerationController.run` 只填充单条 `genMessage`（起始把空目标行落盘、结束再写最终行，中途**不逐帧写库**），流式内容存 `ChatViewModel.streamingMessage`（按 `genTargetTs` 在渲染列表里覆盖/追加对应行）——避免每帧 DB 写触发分页刷新。仅存开场白、尚未发消息的新会话不落盘、分页为空，UI 回退到 `session.messages` 显示开场白；首次改动（如切换备选开场白）由 `ensurePersisted` 惰性整存落盘。消息附件由 `data/chat/AttachmentStore` 在发送时拷入 `attachments/`（图片降采样重编码为 JPEG，content URI 权限是临时的，必须落自有副本），消息里存 filesDir 相对路径；`ChatRepository.clear` 会连带清空该目录。

**SillyTavern 兼容性**是数据层的核心：`CharacterRepository.import` 可以从纯 JSON 文件、或从 PNG 的 `tEXt`/`zTXt` 块（关键字为 `chara`/`ccv3`，base64/zlib 编码）中提取角色 JSON，并自动将内嵌的 `character_book` 提取为独立的世界书文件。导入/导出格式需保持与 SillyTavern 兼容。正则脚本有两个来源：角色卡内嵌（`extensions.regex_scripts` → `CharacterCard.regexScripts`，随角色卡生效）与**预设私有**（存进预设 JSON 的 `regex_scripts` 数组 → `StPreset.regexScripts`，`RegexScript.toCharRegex()` 转成统一类型，**只在关联该预设的聊天里生效**——不再有全局 `regex/` 目录；在 `PresetEditorScreen` 的「正则」Tab 内导入/编辑/删除，随预设一起落盘），统一由 `data/character/RegexEngine` 套用（对齐 ST 的 JS `/pattern/flags` 字面量与 `$1`/`{{match}}` 替换语法）——**显示侧** `applyForDisplay`（`ChatMessageContent` 按消息深度用 `depthKey` 做 remember 缓存，避免新消息到达时重算旧消息）与**发送侧** `applyForPrompt`（`PromptBuilder.assemble` 构建请求时逐条历史套用）互补：`promptOnly` 只在发送侧生效、`markdownOnly` 只在显示侧生效、两个标志都为 false 的两侧都生效。

**尚未接线的功能**：世界书激活引擎已覆盖 ST 的高价值子集（constant、正则/普通关键词、次级关键词 selectiveLogic、probability 掷骰、条目级 scanDepth/caseSensitive/matchWholeWords 覆盖、递归激活、inclusion group、sticky/cooldown/delay 定时效果，默认扫描深度同 ST 为 2 条消息），但 **minActivations、token 预算参与激活、向量化/语义激活** 未实现（前者需全局 WI 设置界面，后者需 embedding 后端）；群组聊天（talkativeness/群卡合并）未实现。采样参数下发覆盖 temperature/topP/topK/maxTokens 与 frequency/presence penalty、seed（各 provider 按官方支持度路由，Claude 不支持 penalty/seed）。

## 约定

- **公共 UI 组件**：`ui/components/` 存放跨页面复用的 composable —— `AppTopBar`（返回键 + 居中标题页头）、`ConfirmDeleteDialog`/`RenameDialog`、`LoadingState`/`EmptyState`（列表页加载/空态）、`ImportableListScreen`（可导入条目的通用列表页，预设/世界书列表即其薄壳；角色列表因拖拽排序/导出菜单差异未套用）、`rememberReorderableList`（长按拖拽排序状态，封装 sh.calvin.reorderable，按 key 反查下标换位以避开头部项下标错位）、`Spacing.kt`（`Space4/8/12/16` 间距常量）、`Interactions.kt`（`appClickable` 单击+可选长按一体，含 Material 波纹与长按 haptic；`noRippleClickable` 气泡内贴边小图标的无波纹点击；`draggableLiftScale` 拖拽抬起的统一缩放过渡）、`AppIconButton`（统一图标按钮：圆形可点击区、圆角全圆，`container` 透明=点击才显圆形按压背景、给色=常驻底色圆按钮；约定返回键/主导航图标用常驻底色、内联操作小图标用点击显背景）。新页面一律复用这些组件，不要在页面文件里重新手写同款页头/对话框/间距常量/点击手势（尤其不要再散装 `pointerInput { detectTapGestures }` 或 `clickable(indication = null, …)`）。
- **国际化**：默认字符串（`values/strings.xml`）为中文；英文在 `values-en/strings.xml`。每个面向用户的字符串都必须同时添加到两处。应用内语言切换由 `data/settings/LanguageStore` + `MainActivity` 中的 `AppCompatDelegate` 处理（切换语言会重启 Activity，并通过 `LanguageStore.consumePendingChange` 重新打开设置页）。
- **图标**：使用 Lucide 图标（`com.composables.icons.lucide`），不要使用 Material 图标 — UI 遵循基于 Lucide 的 Figma 设计稿。
- **Markdown**：消息正文用 mikepenz `multiplatform-markdown-renderer-m3`（纯 Compose）渲染。**流式生成期间也实时走同一条 Markdown 管线**（`MessageContent` 不再区分流式/结束——都套正则+宏后同步解析渲染，正文尚空的纯思考阶段显示 `StreamingDots` 呼吸点），每 60ms 节流帧解析一次；同步解析保证内容一变即同帧成型到真实高度，流式增长平滑跟随、切分支/重试的同帧锚定不抖。
- **主题**：浅色/深色模式通过 `data/settings/ThemeStore`（`ThemeMode`）实现，在 `ui/theme/Theme.kt` 中应用；全局字体缩放通过 `data/settings/FontSizeStore` 实现。
