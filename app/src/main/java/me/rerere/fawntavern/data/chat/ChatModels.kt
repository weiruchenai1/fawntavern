package me.rerere.fawntavern.data.chat

import kotlinx.serialization.Serializable

/**
 * 消息的一个版本（重新生成的历史版本 / 备选开场白）。
 * 版本只是同一位置消息的不同内容：切换版本仅改变 ChatMessage.altIdx 与镜像字段，
 * 本消息之后的时间线由所有版本共享，不随版本切换变化。
 */
@Serializable
data class MsgAlt(
    val content: String = "",
    val reasoning: String = "",
    val model: String = "",
    val reasoningMs: Long = 0,
)

/** 非图片附件：name 为原始文件名（显示用），path 为 filesDir 相对路径 */
@Serializable
data class MsgFile(
    val name: String = "",
    val path: String = "",
)

/** 单条聊天消息。content/reasoning/model/reasoningMs 始终镜像 alts[altIdx]（alts 为空表示只有单版本） */
@Serializable
data class ChatMessage(
    val role: String,             // "user" | "assistant"
    val content: String = "",
    val reasoning: String = "",   // 思考过程（如 deepseek-reasoner / claude thinking）
    val model: String = "",       // 生成该消息的模型 ID（仅 assistant；开场白为空）
    val reasoningMs: Long = 0,    // 思考耗时（毫秒）
    val ts: Long = System.currentTimeMillis(),
    val alts: List<MsgAlt> = emptyList(),  // 多版本（含当前版本）
    val altIdx: Int = 0,
    val images: List<String> = emptyList(),   // 图片附件（filesDir 相对路径，发送时编码为 base64）
    val files: List<MsgFile> = emptyList(),   // 其它文件附件（发送时尝试以文本内联进 prompt）
)

/** 聊天会话：每个角色卡对应独立的聊天列表 */
data class ChatSession(
    val id: String = java.util.UUID.randomUUID().toString(),
    val charFile: String = "",    // 角色卡文件名，空 = 无角色的普通聊天
    val charName: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val timedWi: Map<String, Int> = emptyMap(),  // 世界书定时效果状态（sticky/cooldown 跨轮持久化）
    val extState: Map<String, String> = emptyMap(),  // 每扩展的会话级状态 blob（extId → JSON 串，如摘要）
)
