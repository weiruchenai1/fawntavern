# CLAUDE.md

本文件为 Claude Code (claude.ai/code) 在此仓库中工作时提供指导。

## 项目

FawnTavern 是一款轻量 AI 角色扮演聊天的 Android 客户端（Kotlin + Jetpack Compose，Material 3）。支持导入 SillyTavern 兼容的角色卡、预设和世界书，可对接多种大模型API，随时随地与喜欢的角色进行聊天。包名：`me.rerere.fawntavern`。

## 常用命令

```bash
./gradlew assembleDebug      # 构建 debug APK
./gradlew installDebug       # 构建并安装 debug 版到已连接的设备/模拟器
./gradlew assembleRelease    # 构建 release APK
./gradlew installRelease     # 构建并安装 release 版到已连接的设备/模拟器
./gradlew testDebugUnitTest  # 运行 debug JVM 单元测试
./gradlew lint               # 运行 Android 静态检查
```

- 需要 JDK 17；minSdk 26、compile/targetSdk 37；Android SDK 路径在 `local.properties` 中。
- debug 版包名带 `.debug` 后缀，与 release 版可并存安装、数据互不干扰。
- release 签名读根目录的 `keystore.properties`（不入库）；文件缺失时 release 走未签名，debug 构建不受影响。
- JVM 单元测试位于 `app/src/test`，通过 `testDebugUnitTest` 运行；Compose 仪器测试位于 `app/src/androidTest`，由 GitHub Actions 的 Android 模拟器执行。
- 依赖版本集中在 `gradle/libs.versions.toml`（AGP 9.x、Kotlin 2.4、Compose BOM）。
- 开发过程中的验证方式是安装到设备/模拟器上实际运行查看。

## 架构

单 Activity 的 Compose 应用，**没有使用导航库**。`MainActivity` 渲染 `ChatScreen`（`ui/chat/ChatScreen.kt`）；`ui/chat/ChatNavigationState.kt` 定义可保存的 `ChatDestination` 返回栈，`ui/chat/ChatDestinationHost.kt` 负责渲染栈顶全屏页面，返回即弹栈。新增页面：增加一个 `ChatDestination` 枚举值、在 `ChatDestinationHost` 增加分支，并从入口调用 `nav.add(...)`。模型/角色选择等弹层仍由聊天页持有。

**消息列表的滚动位置由 `ui/chat/ChatScrollController.kt` 单一持有**，`ChatScreen` 不直接碰 `LazyListState`，只在每次重组把外部依赖刷进 `scrollCtrl.inputs`。位置变更只有两个原语，**不可混用**：`requestScrollToItem`（登记待生效位置、下一次**测量**采用，内容增长与位置更新同帧完成，无先画错再纠正的闪烁；钉底传越界索引，clamp 后停在列表末尾的 1dp 锚点行）用于钉底与锚定；`animateScrollToItem`/`scrollToItem` 走 `scroll {}`，仅用于导航按钮跳转——`requestScrollToItem` 在 `isScrollInProgress` 时会自己 `launch { scroll {} }` 抢占，塞进 `scroll {}` 块里等于取消自己。三条到底部的路径：`snapToBottom()` 单帧（流式跟随、IME 高度变化）、`pinToBottom()` 同帧登记 + 跨帧收敛（发送/切会话/点回到底部——Markdown 异步解析，远距离跳转要几帧才长到真实高度）、`switchAnchored()` 切分支落点锚定。并发安全靠 `pinJob` + `MutatorMutex`：任何新钉底**原子作废**在途的那个，绝不允许两个收敛循环互抢。跟随规则只有两条——**上划即停跟**、**落点触底即跟随**，都在 `settleDrag()`（手指抬起且惯性走完）一处判；fling 阶段靠 `dragging` 标志让位，不让位会把用户甩出的惯性硬截停。右下角的悬浮导航按钮栏（`ui/chat/ScrollNavButtons.kt`：顶部/上一条/下一条/底部）滚动时出现、静止两秒后隐藏，连续点上/下一条靠 `navAnchorIndex` 链式推进（不受动画途中 `firstVisibleItemIndex` 尚未落位的影响），手指一碰即失效。控制器实例提升到 `when (nav.lastOrNull())` **之上**（全屏页面命中时聊天区域整个离开组合，其内 remember 全毁），但 `runLoops()` 的两个长循环留在聊天区域内，随其在屏/离屏启停。

**聊天状态入口在 `ui/chat/ChatViewModel.kt`**（`AndroidViewModel` + Compose `mutableStateOf`）。`ChatContract.kt` 用分组的 `ChatUiState` 暴露渲染快照，用 `ChatAction`/`dispatch` 统一无返回值动作；`TextFieldState`、滚动位置和需要同步返回 `SendOutcome` 的发送/重试仍保留显式入口，避免破坏 Compose 输入与滚动时序。具体职责继续下沉：`ChatSessionCoordinator` 负责会话创建、打开、删除后的候选选择、重命名和置顶，`ChatAttachmentCoordinator` 负责附件大小校验、URI 落盘和失败清理，`ChatMediaInput` 负责相机临时文件和媒体类型判断，`ChatMessageWindow` 负责分页窗口与实时 overlay 合并，`ChatUiSettingsController` 提供聊天页偏好与字号快照，`ChatWebSearchSettingsController` 统一联网搜索开关、服务列表和选中项，`ChatGenerationCoordinator` 原子抢占/停止单个生成任务，`ChatMessageCoordinator` 处理单条消息操作，`PromptContextLoader` 加载 Prompt 上下文，`ChatTitleGenerator` 生成并保存标题，`ChatTtsController` 管理朗读。设置、API、角色、预设、世界书和扩展页面通过各自的 `*Controller` 与可注入 `*DataSource` 访问存储，页面不直接调用 Store/Repository。可独立测试的业务逻辑主要在 `domain/` 层（均为无 Android UI 依赖的普通 Kotlin）：
- `domain/PromptBuilder` — Prompt 编译：角色卡 + 已加载的世界书/预设 → `Built`（历史前后的提示块、深度注入块、发送侧正则、采样参数、token 上限）；世界书按多轮激活引擎收集条目，预设按 `promptOrder` 编排、marker 映射角色卡字段，角色卡 `system_prompt`/`post_history_instructions` 优先于预设 main/jailbreak，角色 `extensions.depth_prompt` 作深度注入。`domain/PromptMessageAssembler` 每次请求把 `Built` 与聊天历史合成完整 `ApiMessage` 数组：历史逐条套发送侧正则、文件附件内联为 `<file>` 文本块、图片读盘编码 base64、按 `maxContext − maxTokens` token 预算从新到旧裁剪历史、深度注入按位插入。宏（`{{char}}`/`{{user}}`/`{{newline}}`/时间日期/`{{random}}`/`{{roll}}`/`{{pick}}`）由 `domain/MacroEngine` 统一替换，`{{original}}` 在角色卡覆盖预设 main/jailbreak 时引用被覆盖原文。世界书激活引擎（`PromptBuilder.activateWorldInfo`）已实现：constant/关键词扫描 + 次级 selectiveLogic + probability 掷骰 + 条目级 scanDepth/caseSensitive/matchWholeWords 覆盖、**递归激活**（excludeRecursion/preventRecursion/delayUntilRecursion）、**inclusion group 互斥组**（groupOverride/groupWeight/useGroupScoring）、**sticky/cooldown/delay 定时效果**（状态存于 `ChatSession.timedWi`，随会话持久化）。
- `domain/ConversationOps` — 会话/消息的纯变换（新建会话含开场白、追加消息、alts 多版本切换/删除/编辑、重答开新版本 `startVariant`、`nextTs`）。输入旧会话返回新会话，不做 IO。**alts 即同位置多版本**（对齐 RikkaHub 的 MessageNode/selectIndex 模型）：每条消息的多个版本（`MsgAlt`）只是不同的内容，`altIdx` 指向当前显示版本，`content` 等镜像字段始终同步 `alts[altIdx]`；本消息之后的时间线由所有版本共享，切换/删除版本、重答任意 assistant 消息（原位开新版本，下文保留）都不改动其后的消息。
- `domain/GenerationEngine` — 流式生成执行器（含 60ms 节流刷新、停止标志；角色卡 `streaming = false` 时不起节流协程，结束时一次性刷新），通过 `onUpdate` 回调发布中间会话，返回最终会话。

生成协程运行在 `viewModelScope`，Activity 重建（深色模式/语言切换）不中断。发送时用户消息立即落盘，生成结束后再存完整会话。注意：同一会话内消息 `ts` 必须严格递增（`ConversationOps.nextTs`），它是消息列表的 LazyColumn key。

**依赖由 `di/AppContainer.kt` 手工装配，不使用 Hilt；HTTP 直接用 OkHttp。** `FawnTavernApplication` 持有容器，`ChatViewModelFactory` 注入由 `ChatFeatureDependencies` 组装的 Feature 依赖集合。协议无关且跨生成/网络边界使用的值对象位于 `:core:model`（当前为减少迁移噪音暂时保留 `data/api` 包名），包括 `ApiMessage`、`GenParams`、工具调用、图片生成结果和请求快照；该模块不得包含 API Key、base URL、供应商自定义请求字段或 Android 存储实现。`ApiProvider`、`ModelInfo` 等网络配置仍归 `:core:network`，完整供应商配置只在 `AndroidGenerationGateway` 根据 `providerId` 解析，不进入 `GenerationEngine`。纯网络层位于 `:core:network` 模块：`ChatApi` 是流式聊天入口，接收 `PromptMessageAssembler.assemble` 产出的完整 `ApiMessage` 数组（system/user/assistant，可带 base64 图片）与可选采样参数 `GenParams`（来自关联预设），按 `provider.type` 路由到 `ProviderAdapter` 接口的三个实现（`OpenAiAdapter`/`GoogleAdapter`/`ClaudeAdapter`，各自只做协议编解码；Claude/Gemini 不允许消息数组里出现 system —— 开头连续 system 提升为顶层参数、对话中间的降级为 user，并合并相邻同角色消息，共用工具在 `ApiModels.kt`）；SSE 传输统一在 `SseClient`，OkHttp 客户端全局共享在 `Http`（SSE 用读超时放宽到 5 分钟的派生实例，容忍模型思考期间长时间无数据）。停止生成有两条路径：读循环逐行检查停止标志（快路径）+ 监视协程轮询并 `cancel()` OkHttp Call（覆盖线程阻塞在 `read()` 的情况）。`ModelApi` 负责模型列表/余额查询。新增协议：实现一个 `ProviderAdapter` + 在 `ChatApi.adapterFor` 加一个分支。**每个已添加的模型带一份元数据**（`data/api/ModelInfo.kt` 的 `ModelInfo`：展示名、输入/输出模态、能力标记、自定义请求头/请求体、内置工具），存在所属 `ApiProvider.models` 里随 app 模块的 `ApiConfigStore` 落盘。调用方只传模型 ID，`ChatApi.streamChat` 自己从提供商配置里找回 `ModelInfo` 交给 Adapter；Adapter 在收尾处套上自定义请求头/请求体（`applyHeaders`/`applyCustomBodies`，同名覆盖协议自己填的字段），内置工具（搜索 / URL 上下文）按协议下发。**App 侧函数工具（function calling）已接线**：`ChatApi.streamChat` 接收 `ToolSpec` 列表并返回 `StreamEnd`（含模型发起的 `ApiToolCall`），三个 Adapter 各自解析流式工具调用（OpenAI 的 `delta.tool_calls` 按 index 分片累积；Gemini 的 `functionCall` part 整块到达、`thoughtSignature` 存进 `ApiToolCall.extra` 回传时回显；Claude 按 content block index 跟踪 `tool_use`/`input_json_delta`，thinking 块连 `signature` 原样捕获进 `ApiMessage.rawBlocks` 回显，否则被拒）；带 `toolCalls` 的 assistant `ApiMessage` 由 Adapter 展开成"assistant 调用 + 工具结果"两条协议消息（工具轮次只存在于单次生成的循环内，不进聊天历史落盘）。**多轮工具循环在 `GenerationEngine`**：它只依赖 `GenerationGateway`，流式一轮 → 有工具调用则经 `GenerationToolExecutor` 执行、结果拼回消息数组续下一轮，正常轮上限 3、硬上限 5（超限回错误 JSON 引导模型直接作答，杜绝无限循环）；思考耗时分段累计（工具执行期间不计入）。聊天侧由 `PrepareChatGenerationUseCase` 组装权威会话快照与 Prompt，`CommitChatGenerationUseCase` 原子提交最终消息和世界书计时状态，`ChatGenerationRunner` 只编排变量提交/回滚、生成和最终提交。**联网搜索即基于此**：开关开启且模型未启内置搜索时（内置搜索与函数工具在 Gemini 上不允许并存），注册 `search_web` 工具（唯一参数 `query`），由模型思考后自行提炼关键词、决定搜几次；`ChatSearchTool` 通过可注入数据源执行当前搜索服务，每次调用产出一条 `MsgSearch(query, provider, items, reasoningChars, reasoningMs)` 存进 `ChatMessage.searches`。模态、内置工具和思考预算的协议翻译仍分别由 `ModelRegistry`、`BuiltInTool.supportedBy` 与各 Adapter 负责。持久化有两种惯用方式，底层仍沿用现有 Store/Repository：

- `*Store` 对象 — 设置存储在 `SharedPreferences` 中，使用 `org.json` 手写 JSON 序列化。除 `data/api/ApiConfigStore` 外，其余都在 `data/settings/`（`LanguageStore`、`ThemeStore`（含 `ThemeMode` 枚举）、`FontSizeStore`、`UserProfileStore`、`SearchHistoryStore`）。API、搜索和 TTS 配置以 `schema_version=1` 为持久化基线，可移植备份带 `formatVersion`；以后修改结构必须保留旧版本解析或显式迁移，损坏数据恢复默认值并提示。
- `*Repository` 对象（`data/character`、`data/preset`、`data/worldbook`）— 每个条目对应 `filesDir` 子目录下的一个 JSON 文件，每个领域配有对应的 `*Parser` 和 `*Models` 文件。Repository 提供 `load/save/import/delete/rename/clear` 等挂起函数；目录/文件级共用操作抽在 `data/JsonFileDir`。UI 页面不直接访问 Repository、SharedPreferences 或目录：搜索通过 `ChatSearchController`，数据统计/清理/备份通过 `DataManagementController`；Android 数据源实现负责连接实际存储。
- **聊天记录是例外**：`data/chat/ChatRepository` 底层是 Room 数据库（`data/chat/ChatDatabase.kt`，`sessions` + `messages` 两张表，消息主键为 `(sessionId, ts)`，alts/附件列用 kotlinx.serialization 编码）。v9 是公开发布迁移基线，后续每次 schema 变更都必须注册显式 `Migration`、提交 `app/schemas` 快照并增加旧库升级测试，禁止 destructive migration。对外提供 `sessionsFlow`、`list/save/delete/clear` 和 `messagesPaged`。消息列表按 Paging 3 渲染，所有消息修改统一为按 `ts` 的单条 DB 操作；流式生成走 `streamingMessage` 内存 overlay，中途不逐帧写库。消息附件由 `AttachmentStore` 落入应用私有目录，`ChatRepository.clear` 连带清理未使用附件。

消息渲染按职责拆在 `ui/chat/`：`ChatMessageContent` 编排 Markdown/HTML，`ChatCodeBlock` 渲染代码块，`ChatMarkdownTable` 负责表格、复制和图片导出。不要把存储权限、文件导出或大块子渲染逻辑重新塞回 `ChatMessageContent`。

**SillyTavern 兼容性**是数据层的核心：`CharacterRepository.import` 可以从纯 JSON 文件、或从 PNG 的 `tEXt`/`zTXt` 块（关键字为 `chara`/`ccv3`，base64/zlib 编码）中提取角色 JSON，并自动将内嵌的 `character_book` 提取为独立的世界书文件、把**实际**文件名写进卡的 `enabled_world_books`（书名撞车时 `JsonFileDir.uniqueName` 会加后缀，只靠解析层拿 `character_book.name` 兜底会指到同名的别人那本书上）。**卡内 `character_book` 只是导入载荷、不参与激活**（同 ST 的 `checkEmbeddedWorld`：它只用来显示导入按钮，生效与否只看关联），抽出来的独立文件才是唯一事实源——否则编辑/删除条目后卡内那份旧内容还会照旧注入、取消关联也只生效一半；旧版导入的卡由 `CharacterRepository.migrateEmbeddedWorldBooks` 在启动时补抽并写下关联（判据是「有 `character_book` 但没有 `enabled_world_books` 键」，同名书文件已存在则直接关联而不是再抽一份副本，可反复调用）。导出时 `data/worldbook/WorldBookSerializer` 按当前关联的世界书重新生成 `character_book`（关联多本就合并、id 重排避免跨书主键相撞，每条未建模的 ST 私有字段——`automation_id`/`triggers`/`ignore_budget`/`match_*`——从源文件原样搬回 `extensions`），不重新生成的话用户在世界书里的编辑一条都导不出去。导入/导出格式需保持与 SillyTavern 兼容。正则脚本有三个来源：角色卡内嵌（`extensions.regex_scripts` → `CharacterCard.regexScripts`，随角色卡生效）与**预设私有**（存进预设 JSON 的 `regex_scripts` 数组 → `StPreset.regexScripts`，`RegexScript.toCharRegex()` 转成统一类型，**只在关联该预设的聊天里生效**；在 `PresetEditorScreen` 的「正则」Tab 内导入/编辑/删除，随预设一起落盘）与**全局**（`data/regex/GlobalRegexRepository`，落在 `regex/global.json`，与当前角色卡/预设无关、对所有聊天生效），统一由 `domain/RegexEngine` 套用（对齐 ST 的 JS `/pattern/flags` 字面量与 `$1`/`{{match}}` 替换语法）——**显示侧** `applyForDisplay`（`ChatMessageContent` 按消息深度用 `depthKey` 做 remember 缓存，避免新消息到达时重算旧消息）与**发送侧** `applyForPrompt`（`PromptMessageAssembler.assemble` 构建请求时逐条历史套用）互补：`promptOnly` 只在发送侧生效、`markdownOnly` 只在显示侧生效、两个标志都为 false 的两侧都生效。

**世界书功能边界**：激活引擎已实现 constant、正则/普通关键词、次级关键词 selectiveLogic、probability 掷骰、条目级 scanDepth/caseSensitive/matchWholeWords 覆盖、递归激活、inclusion group、sticky/cooldown/delay 定时效果、minActivations 与 token 预算，默认扫描深度同 ST 为 2 条消息。`vectorized` 字段可导入、编辑并原样保存，但向量化条目当前不参与激活，因为尚无 embedding/语义检索后端。群组聊天尚未实现；角色卡的 `talkativeness` 字段仅解析和保留，不参与当前的单角色生成流程，也没有群卡合并逻辑。

**已接线的生成参数**：采样参数下发覆盖 temperature/topP/topK/maxTokens 与 frequency/presence penalty、seed（各 provider 按官方支持度路由，Claude 不支持 penalty/seed）；思考预算档位由聊天输入区模型按钮右侧的按钮切换（`ReasoningPickerSheet` → `ThinkingStore` 按 `"providerId::modelId"` 分模型记忆 → `PromptBuilder` 打进 `GenParams.reasoning` → `data/api/Reasoning.kt` 按 provider 下发）。

**品牌图标统一走 SVG**：提供商（聊天/搜索/TTS）图标是 `assets/icons/{slug}.svg`，全部归一化为 `viewBox="0 0 24 24"` + `width/height="1em"`；用 Coil 渲染（依赖 `coil-compose` + `coil-svg`，`MainActivity` 的 `setSingletonImageLoaderFactory` 注册 `SvgDecoder.Factory(scaleToDensity = true)`）。`ui/api/ProviderIcon.kt` 的 `iconSlug(name)` 把提供商名映射成 slug，`AsyncImage` 加载 `file:///android_asset/icons/{slug}.svg` 并带 `.css("svg { fill: <主题前景色> }")` —— 单色图标（`fill="currentColor"` 或无 fill 继承）按深浅色模式自动染色，彩色图标 path 自带 fill 不受影响；无匹配 slug 时回退首字头像。**新增图标**：去 `https://lobehub.com/zh/icons` 找品牌名，从 `@lobehub/icons-static-svg` 的 CDN 拉 SVG —— `https://registry.npmmirror.com/@lobehub/icons-static-svg/latest/files/icons/{name}.svg`（单色）/`{name}-color.svg`（彩色），国外可换 `cdn.jsdelivr.net/npm/@lobehub/icons-static-svg@latest/icons/`。要点：① `icons.lobehub.com/icons/*.png` 返回的是网站 HTML 不是真图；② 部分品牌 lobehub 只有单色（openai/groq/ollama/jina/xiaomimimo/moonshot 无 `-color`），lobehub 没有的（duckduckgo/linkup/metaso/serper/querit）在 `D:\kaifa\kelivo\assets\icons\` 有；③ viewBox 非 24×24 时用 `<g transform="translate(tx ty) scale(s)">` 包一层居中缩放（`s=24/max(W,H)`、`tx=(24−W·s)/2−minX·s`），且根节点 `fill` 要留在 `<svg>` 上、别挪进 `<g>`（否则 CSS 染色失效）；④ `fill="black"` 深色模式会隐形，改成 `fill="currentColor"` 或无 fill。

## 约定

- **注释**：一律用简体中文，且尽量精简。只写代码本身读不出来的「为什么」——踩过的坑、竞态与时序、看似多余的写法为何必要；不要复述代码在做什么，不要留框架常识、外部项目对比、TODO 式旁白和过时描述。改了实现就同步改注释，宁可删掉也不要留错的。
- **公共 UI 组件**：`ui/components/` 存放跨页面复用的 composable —— `AppTopBar`（返回键 + 居中标题页头）、`SettingsSubPage`（设置类页面的统一外壳：顶部固定导航栏 `AppTopBar` + 下方可滚动内容，页头到内容、各段间距统一为 `spacing`（默认 16）、两侧 16dp。**顶部 16dp 放 scroll 内层随内容滚动**——放外层会让内容在页头下 16dp 处被裁剪，滚动时看着像页头变高；「整页可滚动的分组列表」型设置页一律用它。LazyColumn 型列表页顶部间距统一用 `contentPadding = PaddingValues(top = 16)`，**勿用首项 `item { Spacer(16) }`**——它是列表项，会被 `spacedBy` 叠加成 28dp）、`ConfirmDeleteDialog`/`RenameDialog`、`LoadingState`/`EmptyState`（列表页加载/空态）、`ImportableListScreen`（可导入条目的通用列表页，预设/世界书列表即其薄壳；角色列表因拖拽排序/导出菜单差异未套用）、`rememberReorderableList`（长按拖拽排序状态，封装 sh.calvin.reorderable，按 key 反查下标换位以避开头部项下标错位）、`Spacing.kt`（`Space4/8/12/16` 间距常量）、`Interactions.kt`（`appClickable` 单击+可选长按一体，含 Material 波纹与长按 haptic；`noRippleClickable` 气泡内贴边小图标的无波纹点击；`draggableLiftScale` 拖拽抬起的统一缩放过渡）、`AppIconButton`（统一图标按钮：圆形可点击区、圆角全圆，`container` 透明=点击才显圆形按压背景、给色=常驻底色圆按钮；约定返回键/主导航图标用常驻底色、内联操作小图标用点击显背景）。新页面一律复用这些组件，不要在页面文件里重新手写同款页头/对话框/间距常量/点击手势（尤其不要再散装 `pointerInput { detectTapGestures }` 或 `clickable(indication = null, …)`）。
- **国际化**：默认字符串（`values/strings.xml`）为中文；英文在 `values-en/strings.xml`。每个面向用户的字符串都必须同时添加到两处。应用内语言切换由 `data/settings/LanguageStore` + `MainActivity` 中的 `AppCompatDelegate` 处理（切换语言会重启 Activity，并通过 `LanguageStore.consumePendingChange` 重新打开设置页）。
- **图标**：使用 Lucide 图标（`com.composables.icons.lucide`），不要使用 Material 图标 — UI 遵循基于 Lucide 的 Figma 设计稿。
- **Markdown**：消息正文用 mikepenz `multiplatform-markdown-renderer-m3`（纯 Compose）渲染。**流式生成期间也实时走同一条 Markdown 管线**（`MessageContent` 不再区分流式/结束——都套正则+宏后同步解析渲染，正文尚空的纯思考阶段显示 `StreamingDots` 呼吸点），每 60ms 节流帧解析一次；同步解析保证内容一变即同帧成型到真实高度，流式增长平滑跟随、切分支/重试的同帧锚定不抖。
- **主题**：浅色/深色模式通过 `data/settings/ThemeStore`（`ThemeMode`）实现，在 `ui/theme/Theme.kt` 中应用；全局字体缩放通过 `data/settings/FontSizeStore` 实现。
- **提交消息**：描述当前代码实现了什么（最终状态，如「新增 X」「统一 Y」「升级 Z」），不要罗列开发过程中遇到的问题或修复的 bug。

## 发布版本

确认待发布代码已经提交并推送到 `main` 后，根据版本类型创建并推送标签。

Beta 预发布版本：

```bash
git tag -a vX.Y.Z-beta.N -m "vX.Y.Z-beta.N"
git push origin vX.Y.Z-beta.N
```

正式版本：

```bash
git tag -a vX.Y.Z -m "vX.Y.Z"
git push origin vX.Y.Z
```

其中 `X.Y.Z` 分别表示主版本号、次版本号和修订号，`N` 表示 Beta 序号。推送 `v*` 标签会触发 `.github/workflows/publish.yml`，自动构建签名 APK、AAB 并创建 GitHub Release；带 `-beta.N` 的标签会标记为预发布版本，不带后缀的标签会创建正式版本。
