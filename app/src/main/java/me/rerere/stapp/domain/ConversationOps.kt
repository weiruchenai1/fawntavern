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

    /** 左右切换消息版本 = 切换分支：本消息之后的时间线随版本一起换入换出；无实际切换返回 null */
    fun switchAlt(s: ChatSession, idx: Int, dir: Int): ChatSession? {
        val m = s.messages.getOrNull(idx) ?: return null
        if (m.alts.size < 2) return null
        val ni = (m.altIdx + dir).coerceIn(0, m.alts.lastIndex)
        if (ni == m.altIdx) return null
        val target = m.alts[ni]
        val newAlts = m.alts.toMutableList()
        // 换出：当前分支的下文存回当前版本（顺带同步镜像字段，编辑过的内容切走再切回不丢）
        newAlts[m.altIdx] = newAlts[m.altIdx].copy(
            content = m.content, reasoning = m.reasoning, model = m.model,
            reasoningMs = m.reasoningMs, tail = s.messages.drop(idx + 1),
        )
        // 换入：目标版本的下文回到消息列表；其存储的 tail 清空（当前分支的下文以 messages 为准）
        newAlts[ni] = target.copy(tail = emptyList())
        val updated = m.copy(content = target.content, reasoning = target.reasoning,
            model = target.model, reasoningMs = target.reasoningMs, alts = newAlts, altIdx = ni)
        return s.copy(
            messages = s.messages.take(idx) + updated + target.tail,
            updatedAt = System.currentTimeMillis(),
        )
    }

    /**
     * 重答前的准备：截断 idx 之后的消息，并把它们作为当前版本的分支下文存进 alts[altIdx].tail
     * （之后 GenerationController 以 variantOfLast 在该消息上开新版本；切换版本时下文随之恢复）。
     */
    fun truncateForRegenerate(s: ChatSession, idx: Int): ChatSession {
        val m = s.messages[idx]
        // 单版本消息先物化为 alts，分支下文才有归属
        val alts = m.alts.ifEmpty { listOf(MsgAlt(m.content, m.reasoning, m.model, m.reasoningMs)) }
        val ai = m.altIdx.coerceIn(0, alts.lastIndex)
        val newAlts = alts.toMutableList().also {
            it[ai] = it[ai].copy(content = m.content, reasoning = m.reasoning, model = m.model,
                reasoningMs = m.reasoningMs, tail = s.messages.drop(idx + 1))
        }
        return s.copy(
            messages = s.messages.take(idx) + m.copy(alts = newAlts, altIdx = ai),
            updatedAt = System.currentTimeMillis(),
        )
    }

    /** 删除消息：多分支时删当前分支（含其下文），换入相邻分支及其下文；单分支删除整条消息 */
    fun deleteMessage(s: ChatSession, idx: Int): ChatSession? {
        val m = s.messages.getOrNull(idx) ?: return null
        return if (m.alts.size > 1) {
            val newAlts = m.alts.toMutableList().also { it.removeAt(m.altIdx) }
            val newIdx = m.altIdx.coerceAtMost(newAlts.lastIndex)
            val cur = newAlts[newIdx]
            val single = newAlts.size == 1
            newAlts[newIdx] = cur.copy(tail = emptyList())
            val list = s.messages.take(idx).toMutableList()
            list += m.copy(
                content = cur.content, reasoning = cur.reasoning,
                model = cur.model, reasoningMs = cur.reasoningMs,
                alts = if (single) emptyList() else newAlts,
                altIdx = if (single) 0 else newIdx,
            )
            list += cur.tail
            s.copy(messages = list, updatedAt = System.currentTimeMillis())
        } else {
            s.copy(
                messages = s.messages.toMutableList().also { it.removeAt(idx) },
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    fun editMessage(s: ChatSession, idx: Int, content: String): ChatSession? {
        if (idx >= s.messages.size) return null
        val list = s.messages.toMutableList()
        list[idx] = list[idx].copy(content = content)
        return s.copy(messages = list, updatedAt = System.currentTimeMillis())
    }
}
