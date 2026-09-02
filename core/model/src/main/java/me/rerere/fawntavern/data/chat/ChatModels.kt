package me.rerere.fawntavern.data.chat

import kotlinx.serialization.Serializable
import me.rerere.fawntavern.data.api.ApiRequestSnapshot

/**
 * 消息的一个版本（重新生成的历史版本 / 备选开场白）。
 * 版本只是同一位置消息的不同内容：切换版本仅改变 ChatMessage.altIdx 与镜像字段，
 * 本消息之后的时间线由所有版本共享，不随版本切换变化。
 */
@Serializable
data class MsgAlt(
    val content: String = "",
    /** 该回复版本的前端/MVU 私有状态；始终是 JSON 对象字符串。 */
    val dataJson: String = "{}",
    val reasoning: String = "",
    val model: String = "",
    val reasoningMs: Long = 0,
    /** 此版本生成期间产生的联网搜索步骤与引用。 */
    val searches: List<MsgSearch> = emptyList(),
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cachedTokens: Int = 0,
    val generationMs: Long = 0,
    val images: List<String> = emptyList(),
    val imageAspectRatio: String = "2:3",
    val requestSnapshots: List<ApiRequestSnapshot> = emptyList(),
)

/** 联网搜索的单条引用来源（展示"N个引用"胶囊卡与来源列表用） */
@Serializable
data class SearchCitation(
    val title: String = "",
    val url: String = "",
    val text: String = "",
)

/** 一次搜索工具调用：query 为模型自行决定的查询词，provider 为搜索服务商显示名，
 *  items 为引用来源。[reasoningChars]/[reasoningMs] 记录搜索发起时已产生的思考字符数与
 *  已累计思考耗时 —— 时间线卡片按此把整段思考切成"思考→搜索→思考"的交错步骤。
 *  [searching] 仅流式 overlay 用（搜索进行中的时间线状态），不落盘 */
@Serializable
data class MsgSearch(
    val query: String = "",
    val provider: String = "",
    val items: List<SearchCitation> = emptyList(),
    val reasoningChars: Int = 0,
    val reasoningMs: Long = 0,
    @kotlinx.serialization.Transient val searching: Boolean = false,
)

/** 非图片附件：name 为原始文件名（显示用），path 为 filesDir 相对路径 */
@Serializable
data class MsgFile(
    val name: String = "",
    val path: String = "",
)

/** 单条聊天消息。版本相关字段始终镜像 alts[altIdx]（alts 为空表示只有单版本） */
@Serializable
data class ChatMessage(
    val role: String,             // "user" | "assistant"
    val content: String = "",
    /** 当前版本的前端/MVU 私有状态；有 alts 时始终镜像 alts[altIdx].dataJson。 */
    val dataJson: String = "{}",
    val isHidden: Boolean = false,
    val reasoning: String = "",   // 思考过程（如 deepseek-reasoner / claude thinking）
    val model: String = "",       // 生成该消息的模型 ID（仅 assistant；开场白为空）
    val reasoningMs: Long = 0,    // 思考耗时（毫秒）
    val ts: Long = System.currentTimeMillis(),
    val alts: List<MsgAlt> = emptyList(),  // 多版本（含当前版本）
    val altIdx: Int = 0,
    val images: List<String> = emptyList(),   // 图片附件（filesDir 相对路径，发送时编码为 base64）
    val imageAspectRatio: String = "2:3",    // 生成图片时选中的比例，供历史缩略图按比例布局
    val files: List<MsgFile> = emptyList(),   // 其它文件附件（发送时尝试以文本内联进 prompt）
    val searches: List<MsgSearch> = emptyList(),  // 当前版本的搜索工具调用（仅 assistant；按调用顺序）
    val promptTokens: Int = 0,       // 本次请求输入 token（API usage 优先，缺失时估算）
    val completionTokens: Int = 0,   // 本次生成输出 token（API usage 优先，缺失时估算）
    val cachedTokens: Int = 0,       // 本次请求由供应商缓存命中的输入 token
    val generationMs: Long = 0,      // 从发起请求到生成收尾的总用时
    val requestSnapshots: List<ApiRequestSnapshot> = emptyList(), // 当前版本各轮实际请求，不含请求头
)

/** 聊天会话：每个角色卡对应独立的聊天列表 */
@Serializable
data class ChatSession(
    val id: String = java.util.UUID.randomUUID().toString(),
    val charFile: String = "",    // 角色卡文件名，空 = 无角色的普通聊天
    val charName: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val localVariables: Map<String, String> = emptyMap(),
    val timedWi: Map<String, Int> = emptyMap(),  // 世界书定时效果状态（sticky/cooldown 跨轮持久化）
    val extState: Map<String, String> = emptyMap(),  // 每扩展的会话级状态 blob（extId → JSON 串，如摘要）
    val title: String = "",  // 会话标题：标题模型自动生成，空时 UI 回退到首条消息预览
    val pinned: Boolean = false,  // 是否固定在抽屉列表顶部
)
