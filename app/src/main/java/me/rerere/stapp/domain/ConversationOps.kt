package me.rerere.stapp.domain

import me.rerere.stapp.data.character.CharacterCard
import me.rerere.stapp.data.chat.ChatMessage
import me.rerere.stapp.data.chat.ChatSession
import me.rerere.stapp.data.chat.MsgAlt
import me.rerere.stapp.data.chat.MsgFile

/**
 * 会话/消息的纯变换：输入旧会话，返回新会话（或 null 表示无变化）。
 * 不做 IO、不持有状态 —— 落盘与状态更新由 ViewModel 负责。
 */
internal object ConversationOps {

    /** 同一会话内消息 ts 必须严格递增：ts 用作消息列表的 LazyColumn key，同毫秒创建会撞 key 崩溃 */
    fun nextTs(s: ChatSession): Long =
        maxOf(System.currentTimeMillis(), (s.messages.maxOfOrNull { it.ts } ?: 0L) + 1)

    /** 新会话：有角色卡时以开场白（first_mes + 备选开场白，可左右切换）作为首条消息 */
    fun newSession(card: CharacterCard?, charFile: String, charNameFallback: String, userName: String): ChatSession {
        val name = (card?.name ?: "").ifBlank { charNameFallback }
        val greetings = buildList {
            card?.firstMes?.takeIf { it.isNotBlank() }?.let { add(PromptBuilder.applyMacros(it, name, userName)) }
            card?.alternateGreetings?.forEach { g ->
                if (g.isNotBlank()) add(PromptBuilder.applyMacros(g, name, userName))
            }
        }
        return ChatSession(
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

    /** 左右切换消息版本：只换本消息显示的内容（下文由所有版本共享，不随切换变化）；无实际切换返回 null */
    fun switchAlt(s: ChatSession, idx: Int, dir: Int): ChatSession? {
        val m = s.messages.getOrNull(idx) ?: return null
        if (m.alts.size < 2) return null
        val ni = (m.altIdx + dir).coerceIn(0, m.alts.lastIndex)
        if (ni == m.altIdx) return null
        val newAlts = m.alts.toMutableList()
        // 镜像字段先写回当前版本：编辑过的内容切走再切回不丢
        newAlts[m.altIdx] = newAlts[m.altIdx].copy(
            content = m.content, reasoning = m.reasoning, model = m.model, reasoningMs = m.reasoningMs)
        val target = newAlts[ni]
        val list = s.messages.toMutableList()
        list[idx] = m.copy(content = target.content, reasoning = target.reasoning,
            model = target.model, reasoningMs = target.reasoningMs, alts = newAlts, altIdx = ni)
        return s.copy(messages = list, updatedAt = System.currentTimeMillis())
    }

    /**
     * 重答准备：在 idx 消息上开一个空白新版本并切换过去（由 GenerationController 流式填充）。
     * 下文保留在 messages 里，由所有版本共享 —— 重答非末条消息不再截断其后的时间线。
     */
    fun startVariant(s: ChatSession, idx: Int, modelId: String): ChatSession {
        val m = s.messages[idx]
        // 单版本消息先物化为 alts；镜像字段写回当前版本，切回旧版本时内容不丢
        val alts = m.alts.ifEmpty { listOf(MsgAlt()) }.toMutableList()
        val ai = m.altIdx.coerceIn(0, alts.lastIndex)
        alts[ai] = alts[ai].copy(content = m.content, reasoning = m.reasoning,
            model = m.model, reasoningMs = m.reasoningMs)
        alts += MsgAlt(model = modelId)
        val list = s.messages.toMutableList()
        list[idx] = m.copy(content = "", reasoning = "", model = modelId, reasoningMs = 0,
            alts = alts, altIdx = alts.lastIndex)
        return s.copy(messages = list, updatedAt = System.currentTimeMillis())
    }

    /** 删除消息：多版本时删当前版本、就近切换相邻版本（下文共享，不受影响）；单版本删除整条消息 */
    fun deleteMessage(s: ChatSession, idx: Int): ChatSession? {
        val m = s.messages.getOrNull(idx) ?: return null
        val list = s.messages.toMutableList()
        if (m.alts.size > 1) {
            val newAlts = m.alts.toMutableList().also { it.removeAt(m.altIdx) }
            val newIdx = m.altIdx.coerceAtMost(newAlts.lastIndex)
            val cur = newAlts[newIdx]
            val single = newAlts.size == 1
            list[idx] = m.copy(
                content = cur.content, reasoning = cur.reasoning,
                model = cur.model, reasoningMs = cur.reasoningMs,
                alts = if (single) emptyList() else newAlts,
                altIdx = if (single) 0 else newIdx,
            )
        } else {
            list.removeAt(idx)
        }
        return s.copy(messages = list, updatedAt = System.currentTimeMillis())
    }

    fun editMessage(s: ChatSession, idx: Int, content: String): ChatSession? {
        if (idx >= s.messages.size) return null
        val list = s.messages.toMutableList()
        list[idx] = list[idx].copy(content = content)
        return s.copy(messages = list, updatedAt = System.currentTimeMillis())
    }
}
