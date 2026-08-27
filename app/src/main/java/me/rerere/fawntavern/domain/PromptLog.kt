package me.rerere.fawntavern.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import me.rerere.fawntavern.data.api.ApiMessage
import me.rerere.fawntavern.data.api.GenParams

/** 最终发送的一条消息（不含 base64 图片，只记录张数，避免日志占用大量内存） */
internal data class LoggedMessage(val role: String, val content: String, val imageCount: Int)

/** 一次生成请求最终组装出的完整 prompt 快照 */
internal data class PromptLogEntry(
    val time: Long,
    val providerName: String,
    val modelId: String,
    val charName: String,
    val presetName: String?,           // null = 未关联预设（走默认拼装）
    val worldInfoCount: Int,           // 激活并插入的世界书条目数
    val messages: List<LoggedMessage>, // 最终发送的完整消息数组
    val params: GenParams?,
    val approxTokens: Int,
)

/**
 * Prompt 调试日志：把每次生成最终组装出的完整 prompt 记录下来，供设置内的"Prompt 日志"页
 * 核对角色卡 / 世界书 / 预设是否已正确插入组装。**纯内存，App 重启即清空**。
 *
 * [enabled] 由设置项控制（[me.rerere.fawntavern.data.settings.PromptLogStore]，默认关闭），
 * 关闭时 [record] 直接返回、零开销；ViewModel 启动时把持久化开关同步到此。
 * 记录点在 [GenerationEngine]：拿到 [PromptMessageAssembler.assemble] 的结果后调用 [record]。
 */
object PromptLog {
    /** 最多保留的日志条数（新的在前，超出丢弃最旧） */
    private const val CAP = 30

    @Volatile
    var enabled: Boolean = false

    private val _entries = MutableStateFlow<List<PromptLogEntry>>(emptyList())
    internal val entries: StateFlow<List<PromptLogEntry>> = _entries

    /** 由生成执行器调用；[enabled] 为 false 时不记录 */
    internal fun record(
        built: PromptBuilder.Built,
        providerName: String,
        modelId: String,
        messages: List<ApiMessage>,
    ) {
        if (!enabled) return
        val wiCount = (built.preHistory + built.postHistory).count { it.source == PromptBuilder.PromptSource.WORLD_INFO } +
            built.depthInjections.count { it.source == PromptBuilder.PromptSource.WORLD_INFO }
        val entry = PromptLogEntry(
            time = System.currentTimeMillis(),
            providerName = providerName,
            modelId = modelId,
            charName = built.charName,
            presetName = built.presetName.takeIf { it.isNotBlank() },
            worldInfoCount = wiCount,
            messages = messages.map { LoggedMessage(it.role, it.content, it.images.size) },
            params = built.genParams,
            approxTokens = messages.sumOf { TokenEstimator.estimate(it.content) },
        )
        _entries.value = (listOf(entry) + _entries.value).take(CAP)
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
