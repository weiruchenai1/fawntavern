package me.rerere.fawntavern.domain

import me.rerere.fawntavern.data.character.CharacterCard
import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.chat.ChatSession
import me.rerere.fawntavern.data.chat.MessageAlternatives
import me.rerere.fawntavern.data.chat.MsgAlt
import me.rerere.fawntavern.data.chat.MsgFile

/**
 * 会话/消息的纯变换：输入旧会话，返回新会话（或 null 表示无变化）。
 * 不做 IO、不持有状态 —— 落盘与状态更新由 ViewModel 负责。
 */
internal object ConversationOps {

    /** 同一会话内消息 ts 必须严格递增：ts 用作消息列表的 LazyColumn key，同毫秒创建会撞 key 崩溃 */
    fun nextTs(s: ChatSession): Long =
        maxOf(System.currentTimeMillis(), (s.messages.maxOfOrNull { it.ts } ?: 0L) + 1)

    /** 新会话：有角色卡时以开场白（first_mes + 备选开场白，可左右切换）作为首条消息 */
    fun newSession(
        card: CharacterCard?,
        charFile: String,
        charNameFallback: String,
    ): ChatSession {
        val sessionId = java.util.UUID.randomUUID().toString()
        val name = (card?.name ?: "").ifBlank { charNameFallback }
        val greetings = buildList {
            card?.firstMes?.takeIf { it.isNotBlank() }?.let {
                add(it)
            }
            card?.alternateGreetings?.forEach { g ->
                if (g.isNotBlank()) add(g)
            }
        }
        return ChatSession(
            id = sessionId,
            charFile = charFile,
            charName = name,
            messages = if (greetings.isEmpty()) emptyList() else listOf(
                ChatMessage(
                    role = "assistant",
                    content = greetings[0],
                    alts = if (greetings.size > 1) greetings.map { MsgAlt(content = it) } else emptyList(),
                    altIdx = 0,
                )
            ),
        )
    }

    /** 追加用户消息（可带图片/文件附件） */
    fun appendUserMessage(
        s: ChatSession,
        text: String,
        images: List<String> = emptyList(),
        files: List<MsgFile> = emptyList(),
    ): ChatSession = s.copy(
        messages = s.messages + ChatMessage(
            role = "user", content = text, ts = nextTs(s), images = images, files = files),
        updatedAt = System.currentTimeMillis(),
    )

    // ── 单条消息的纯变换（DB 粒度落盘用）：只作用于本消息，下文由所有版本共享，不受影响 ──

    /** 左右切换本消息版本：无实际切换返回 null */
    fun switchAltOne(m: ChatMessage, dir: Int): ChatMessage? =
        MessageAlternatives.switch(m, dir)

    /**
     * 删除本消息的当前版本：多版本时就近切到相邻版本并返回新消息；单版本返回 null 表示应整条删除。
     */
    fun deleteAltOne(m: ChatMessage): ChatMessage? =
        MessageAlternatives.deleteCurrent(m)

    /** 重答准备（单消息版）：在本消息上开一个空白新版本并切换过去，由生成器流式填充 */
    fun startVariantOne(m: ChatMessage, modelId: String): ChatMessage {
        val alts = m.alts.ifEmpty { listOf(MsgAlt()) }.toMutableList()
        val ai = m.altIdx.coerceIn(0, alts.lastIndex)
        alts[ai] = alts[ai].copy(content = m.content, reasoning = m.reasoning,
            model = m.model, reasoningMs = m.reasoningMs, searches = m.searches, images = m.images,
            imageAspectRatio = m.imageAspectRatio,
            requestSnapshots = m.requestSnapshots,
            promptTokens = m.promptTokens, completionTokens = m.completionTokens,
            cachedTokens = m.cachedTokens,
            generationMs = m.generationMs)
        alts += MsgAlt(model = modelId)
        return m.copy(content = "", reasoning = "", model = modelId, reasoningMs = 0, images = emptyList(),
            promptTokens = 0, completionTokens = 0, cachedTokens = 0, generationMs = 0,
            requestSnapshots = emptyList(),
            alts = alts, altIdx = alts.lastIndex)
    }
}
