package me.rerere.fawntavern.data.api

import kotlinx.serialization.Serializable
import kotlin.math.round

/** 协议无关的模型消息；ProviderAdapter 负责将其转换为具体供应商协议。 */
data class ApiMessage(
    val role: String,
    val content: String,
    val images: List<ApiImage> = emptyList(),
    val toolCalls: List<ApiToolCall> = emptyList(),
    val rawBlocks: String = "",
)

/** 模型发起的工具调用，以及应用执行后回传给模型的结果。 */
data class ApiToolCall(
    val id: String,
    val name: String,
    val arguments: String,
    val result: String = "",
    val extra: String = "",
)

/** 提供给模型的工具声明，parametersSchema 为 JSON Schema 文本。 */
data class ToolSpec(
    val name: String,
    val description: String,
    val parametersSchema: String,
)

/** 已编码的图片附件，base64 不含 data URL 前缀。 */
data class ApiImage(
    val mimeType: String,
    val base64: String,
)

/** 图片生成模型返回的图片内容，等待上层持久化。 */
data class GeneratedImage(
    val bytes: ByteArray,
    val mimeType: String = "image/png",
)

/** 跨供应商共用的图片生成设置。 */
data class ImageGenerationSettings(
    val count: Int = 1,
    val aspectRatio: String = "2:3",
    val resolution: String = "1k",
    val quality: String = "auto",
    val steps: Int = 9,
    /** null 表示每次生成时随机；非空时固定使用该 Seed。 */
    val seed: Int? = null,
    val includeContext: Boolean = true,
)

/** 协议无关的生成参数；null 字段表示使用供应商默认值。 */
data class GenParams(
    val temperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val maxTokens: Int? = null,
    val frequencyPenalty: Float? = null,
    val presencePenalty: Float? = null,
    val seed: Int? = null,
    val reasoning: ReasoningLevel = ReasoningLevel.AUTO,
    val imageGeneration: ImageGenerationSettings? = null,
)

/** 采样滑块和请求体统一使用两位小数，避免界面显示值与实际下发值不一致。 */
fun Float.roundedSamplingDouble(): Double = round(toDouble() * 100.0) / 100.0

fun Float.roundedSamplingValue(): Float = roundedSamplingDouble().toFloat()

/** AUTO 不下发思考字段，OFF 则显式关闭思考。 */
enum class ReasoningLevel(val budgetTokens: Int, val effort: String) {
    OFF(0, "none"),
    AUTO(-1, "auto"),
    LOW(1_024, "low"),
    MEDIUM(4_096, "medium"),
    HIGH(12_288, "high"),
    XHIGH(24_576, "xhigh");

    val isEnabled: Boolean get() = this != OFF && this != AUTO

    companion object {
        fun fromName(name: String?): ReasoningLevel = entries.firstOrNull { it.name == name } ?: AUTO
    }
}

@Serializable
data class ApiRequestSnapshot(
    val endpoint: String = "",
    val body: String = "",
)

/** 单轮生成的收尾数据；工具调用由生成引擎执行后发起下一轮。 */
data class StreamEnd(
    val toolCalls: List<ApiToolCall> = emptyList(),
    val rawBlocks: String = "",
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cachedTokens: Int = 0,
    val generatedImages: List<GeneratedImage> = emptyList(),
    val requestSnapshot: ApiRequestSnapshot? = null,
)
