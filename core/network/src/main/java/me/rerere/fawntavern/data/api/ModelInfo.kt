package me.rerere.fawntavern.data.api

import org.json.JSONObject
import org.json.JSONTokener

/** 模型的输入/输出模态 */
enum class Modality { TEXT, IMAGE }

/** 模型承担的生成任务；与 provider.type 的网络协议分开。 */
enum class ModelType { CHAT, IMAGE, VIDEO }

/** OpenAI Responses 兼容协议下的图片传输方式：直接图片端点或内置图片工具。 */
enum class ImageGenerationRoute { DIRECT, RESPONSES_TOOL }

/** 聊天模型的 OpenAI 兼容端点选择；默认值兼容 provider 的旧开关。 */
enum class ChatGenerationRoute { PROVIDER_DEFAULT, CHAT_COMPLETIONS, RESPONSES }

fun modelTypeOf(outputModalities: List<Modality>): ModelType =
    if (Modality.IMAGE in outputModalities) ModelType.IMAGE else ModelType.CHAT

/** 模型能力。App 自身不发起工具调用，TOOL 仅作标记；REASONING 也只是标记（思考档位另存 ThinkingStore） */
enum class ModelAbility { TOOL, REASONING }

/** 提供商内置工具：由 API 服务端自带、无需 App 实现，目前只有 Gemini 官方协议支持 */
enum class BuiltInTool { SEARCH, URL_CONTEXT }

/** 自定义请求头 / 请求体条目 */
data class KeyValue(val key: String = "", val value: String = "")

/**
 * 提供商下的一个模型及其元数据。模态与能力是**展示用**的描述（按 ID 猜测、用户可改）；
 * [headers]/[bodies]/[tools] 则会真正改变发出的请求。
 */
data class ModelInfo(
    val id: String = "",
    val displayName: String = "",
    val inputModalities: List<Modality> = listOf(Modality.TEXT),
    val outputModalities: List<Modality> = listOf(Modality.TEXT),
    val abilities: List<ModelAbility> = emptyList(),
    val headers: List<KeyValue> = emptyList(),
    val bodies: List<KeyValue> = emptyList(),
    val tools: Set<BuiltInTool> = emptySet(),
    val type: ModelType = modelTypeOf(outputModalities),
    val imageGenerationRoute: ImageGenerationRoute = ImageGenerationRoute.DIRECT,
    val chatGenerationRoute: ChatGenerationRoute = ChatGenerationRoute.PROVIDER_DEFAULT,
) {
    /** 展示名，没填自定义名称时退回模型 ID */
    val name: String get() = displayName.ifBlank { id }
}

/** 新建模型：按 ID 预填模态与能力 */
fun modelInfoOf(id: String): ModelInfo {
    val caps = ModelRegistry.infer(id)
    return ModelInfo(
        id = id,
        displayName = id,
        inputModalities = caps.input,
        outputModalities = caps.output,
        abilities = caps.abilities,
        type = modelTypeOf(caps.output),
    )
}

internal fun ModelInfo.usesResponsesApi(provider: ApiProvider): Boolean = when (chatGenerationRoute) {
    ChatGenerationRoute.PROVIDER_DEFAULT -> provider.useResponseApi
    ChatGenerationRoute.CHAT_COMPLETIONS -> false
    ChatGenerationRoute.RESPONSES -> true
}

/** 自定义请求头叠在协议自带的鉴权头之上（同名覆盖），空键跳过 */
internal fun ModelInfo.applyHeaders(base: Map<String, String>): Map<String, String> {
    if (headers.isEmpty()) return base
    val out = base.toMutableMap()
    headers.forEach { (k, v) -> if (k.isNotBlank()) out[k.trim()] = v }
    return out
}

/** 自定义请求体字段：值能解析成 JSON 就按 JSON 类型下发，否则当字符串；同名覆盖协议已填的字段 */
internal fun JSONObject.applyCustomBodies(model: ModelInfo) {
    model.bodies.forEach { (k, v) ->
        if (k.isBlank()) return@forEach
        put(k.trim(), runCatching { JSONTokener(v).nextValue() }.getOrNull() ?: v)
    }
}

/**
 * 该内置工具能否在这个提供商上真的下发。内置工具是**协议**能力而非模型能力：
 * 同一个 Gemini 模型，走 Google 原生协议能带 googleSearch，被 OpenAI 兼容网关代理后
 * 请求体里就没有对应字段了。OpenAI 兼容阵营各家的联网开关也互不相同（无统一标准），
 * 只能按主机名分流，认不出的主机不给开关 —— 那种情况用高级设置里的自定义请求体自行下发。
 */
fun BuiltInTool.supportedBy(provider: ApiProvider): Boolean = when (this) {
    BuiltInTool.URL_CONTEXT -> provider.type == "google"
    BuiltInTool.SEARCH -> when (provider.type) {
        "google", "claude" -> true
        else -> openAiSearchStyle(provider.baseUrl) != null
    }
}

/** Gemini 模型可配置 Google 官方内置工具；其他模型按当前提供商的协议能力判断。 */
fun ModelInfo.supportsBuiltInTool(tool: BuiltInTool, provider: ApiProvider): Boolean =
    id.contains("gemini", ignoreCase = true) || tool.supportedBy(provider)

/** OpenAI 兼容端点的联网搜索写法 */
internal enum class OpenAiSearchStyle { OPENROUTER, DASHSCOPE, ZHIPU }

internal fun openAiSearchStyle(baseUrl: String): OpenAiSearchStyle? {
    val host = runCatching { java.net.URI(baseUrl).host?.lowercase() }.getOrNull() ?: return null
    return when {
        host.endsWith("openrouter.ai") -> OpenAiSearchStyle.OPENROUTER
        host.endsWith("dashscope.aliyuncs.com") -> OpenAiSearchStyle.DASHSCOPE
        host.endsWith("bigmodel.cn") -> OpenAiSearchStyle.ZHIPU
        else -> null
    }
}
