package me.rerere.fawntavern.extension

import me.rerere.fawntavern.data.chat.ChatSession

/**
 * 扩展系统的核心契约（SillyTavern 风格「扩展」的本地化实现）。
 *
 * 设计要点：
 * - **能力按接口拆分**：[Extension] 只带元信息；一个扩展按需再实现 [PromptContributor] /
 *   [GenerationLifecycle] 等能力接口（显示/UI 插槽等 Compose 侧能力放 ui 层，另行定义），
 *   消费方用 `filterIsInstance<T>()` 取用。官方扩展与第三方走同一套接口（dogfood）。
 * - **分层**：本文件只放纯逻辑契约（无 Android/Compose 依赖），产出的是可直接喂给
 *   [me.rerere.fawntavern.domain.PromptBuilder] 的中性数据。
 * - **Phase 2**：第三方 JS 扩展（QuickJS）通过桥接实现同样的能力接口；因该运行时无
 *   Promise/异步，[ExtensionServices] 的 suspend 方法会在 JS 线程上以同步阻塞方式适配。
 */
interface Extension {
    val info: ExtensionInfo
}

/**
 * 扩展元信息。[name]/[description] 用纯文本（便于第三方/JS 直接提供）；内置扩展的本地化
 * 展示名由管理界面按 [id] 映射到字符串资源，第三方回退到这里的纯文本。
 */
data class ExtensionInfo(
    val id: String,
    val name: String,
    val description: String = "",
    val builtin: Boolean = true,
    /** 首次使用时（无已存启用记录）的默认启用状态 */
    val defaultEnabled: Boolean = true,
)

// ── 能力：提示注入 ──────────────────────────────────────────

/**
 * 提示贡献者：生成请求前被调用，返回要**并入历史之外**的提示块。这些块进入
 * [me.rerere.fawntavern.domain.PromptBuilder.Built] 的 pre/post/深度注入桶，属"固定块"、
 * 不参与历史的 token 预算裁剪（摘要块因此天然不会被从新到旧裁掉）。
 */
interface PromptContributor {
    fun contribute(ctx: PromptContext): PromptContribution
}

/** 提示贡献上下文。[extState] 为本扩展在当前会话的状态 blob（JSON 串，空 = 无）。 */
data class PromptContext(
    val session: ChatSession,
    val charName: String,
    val userName: String,
    val extState: String = "",
    val config: String = "",   // 本扩展的全局配置 JSON 串（ExtensionStore.getConfig）
)

/** 中性提示块（role/content(/depth)）。由消费方包装成 PromptBuilder.Piece 并标注来源为扩展。 */
data class ExtPiece(val content: String, val role: String = "system")

data class ExtDepthPiece(val content: String, val role: String = "system", val depth: Int = 0)

data class PromptContribution(
    val preHistory: List<ExtPiece> = emptyList(),
    val postHistory: List<ExtPiece> = emptyList(),
    val depthInjections: List<ExtDepthPiece> = emptyList(),
    /** 告知系统可以跳过历史消息的前 N 条（0-based 索引），这些消息已被本扩展压缩（如摘要）。-1 = 不跳过 */
    val skipMessagesUpTo: Int = -1,
) {
    companion object {
        val EMPTY = PromptContribution()
    }
}

// ── 能力：生成生命周期 ──────────────────────────────────────

/**
 * 生成生命周期钩子。[onGenerationComplete] 在一次生成完成、会话已落盘后于**后台协程**调用，
 * 扩展可借 [ExtensionServices] 调模型并回写自身会话级状态（写入走"读最新会话 → 补字段 → save"，
 * 避免与并发用户操作互相覆盖）。
 */
interface GenerationLifecycle {
    suspend fun onGenerationComplete(ctx: GenerationContext, services: ExtensionServices)
}

/** 生命周期上下文。[session] 为刚落盘的完整会话；[extState] 为本扩展当前会话级状态。 */
data class GenerationContext(
    val session: ChatSession,
    val charName: String,
    val userName: String,
    val extState: String = "",
    val config: String = "",   // 本扩展的全局配置 JSON 串（ExtensionStore.getConfig）
)
