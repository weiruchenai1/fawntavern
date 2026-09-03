package me.rerere.fawntavern.plugin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import me.rerere.fawntavern.data.chat.ChatSession
import me.rerere.fawntavern.extension.ExtDepthPiece
import me.rerere.fawntavern.extension.ExtPiece
import me.rerere.fawntavern.extension.Extension
import me.rerere.fawntavern.extension.ExtensionInfo
import me.rerere.fawntavern.extension.ExtensionServices
import me.rerere.fawntavern.extension.GenerationContext
import me.rerere.fawntavern.extension.GenerationLifecycle
import me.rerere.fawntavern.extension.PromptContext
import me.rerere.fawntavern.extension.PromptContribution
import me.rerere.fawntavern.extension.PromptContributor
import me.rerere.fawntavern.plugin.runtime.PluginWorkerClient
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Adapts one installed JavaScript plugin to the app's native extension contract. */
class PluginExtension internal constructor(
    private val plugin: PluginRepository.InstalledPlugin,
) : Extension, PromptContributor, GenerationLifecycle {
    override val info = ExtensionInfo(
        id = plugin.manifest.id,
        name = plugin.manifest.name,
        description = plugin.manifest.description,
        builtin = false,
        defaultEnabled = false,
    )

    override suspend fun contribute(ctx: PromptContext): PromptContribution {
        if (PluginCapabilities.PROMPT_CONTRIBUTOR !in plugin.manifest.capabilities) {
            return PromptContribution.EMPTY
        }
        return callPlugin(
            capability = PluginCapabilities.PROMPT_CONTRIBUTOR,
            method = "contribute",
            argumentJson = PluginPromptCodec.encodeContext(ctx),
            configJson = ctx.config,
            timeoutMs = PROMPT_TIMEOUT_MS,
            fallback = PromptContribution.EMPTY,
        ) { raw ->
            PluginPromptCodec.decodeContribution(raw, ctx.session.messages.lastIndex)
        }
    }

    override suspend fun onGenerationComplete(ctx: GenerationContext, services: ExtensionServices) {
        if (PluginCapabilities.GENERATION_LIFECYCLE !in plugin.manifest.capabilities) return
        callPlugin(
            capability = PluginCapabilities.GENERATION_LIFECYCLE,
            method = "onGenerationComplete",
            argumentJson = PluginPromptCodec.encodeContext(ctx),
            configJson = ctx.config,
            timeoutMs = LIFECYCLE_TIMEOUT_MS,
            fallback = Unit,
        ) { raw ->
            PluginPromptCodec.requireSuccess(raw)
            Unit
        }
    }

    private suspend fun <T> callPlugin(
        capability: String,
        method: String,
        argumentJson: String,
        configJson: String,
        timeoutMs: Long,
        fallback: T,
        decode: (String) -> T,
    ): T = try {
        val raw = PluginWorkerClient.invoke(
            plugin = plugin,
            capability = capability,
            method = method,
            argumentJson = argumentJson,
            configJson = configJson,
            timeoutMs = timeoutMs,
        )
        val result = decode(raw)
        PluginManager.recordSuccess(info.id)
        result
    } catch (error: TimeoutCancellationException) {
        PluginManager.recordFailure(info.id, "插件执行超时", fatal = true)
        fallback
    } catch (error: CancellationException) {
        throw error
    } catch (error: PluginWorkerClient.PluginWorkerException) {
        PluginManager.recordFailure(info.id, error.message.orEmpty(), fatal = error.fatal)
        fallback
    } catch (error: Exception) {
        PluginManager.recordFailure(info.id, error.message.orEmpty(), fatal = false)
        fallback
    }

    private companion object {
        const val PROMPT_TIMEOUT_MS = 1_000L
        const val LIFECYCLE_TIMEOUT_MS = 2_000L
    }
}

internal object PluginPromptCodec {
    fun encodeContext(ctx: PromptContext): String = encodeContext(
        session = ctx.session,
        charName = ctx.charName,
        userName = ctx.userName,
        extState = ctx.extState,
    )

    fun encodeContext(ctx: GenerationContext): String = encodeContext(
        session = ctx.session,
        charName = ctx.charName,
        userName = ctx.userName,
        extState = ctx.extState,
    )

    private fun encodeContext(
        session: ChatSession,
        charName: String,
        userName: String,
        extState: String,
    ): String {
        require(extState.toByteArray(Charsets.UTF_8).size <= MAX_STATE_BYTES) {
            "插件状态超过 128KB"
        }
        var totalBytes = 0
        val selected = ArrayList<JSONObject>()
        for (message in session.messages.asReversed()) {
            if (selected.size >= MAX_MESSAGES) break
            val content = message.content.take(MAX_MESSAGE_CHARS)
            val bytes = content.toByteArray(Charsets.UTF_8).size
            if (totalBytes + bytes > MAX_CONTEXT_CONTENT_BYTES) break
            totalBytes += bytes
            selected += JSONObject()
                .put("role", message.role)
                .put("content", content)
                .put("ts", message.ts)
        }
        selected.reverse()

        while (true) {
            val messages = JSONArray()
            selected.forEach(messages::put)
            val encoded = JSONObject()
                .put("sessionId", session.id)
                .put("charName", charName.take(MAX_NAME_CHARS))
                .put("userName", userName.take(MAX_NAME_CHARS))
                .put("extState", decodeState(extState))
                .put("messages", messages)
                .toString()
            if (encoded.toByteArray(Charsets.UTF_8).size <= MAX_ARGUMENT_BYTES) return encoded
            if (selected.isEmpty()) error("插件上下文超过 192KB")
            selected.removeAt(0)
        }
    }

    fun requireSuccess(envelopeJson: String): JSONObject? {
        val envelope = JSONObject(envelopeJson)
        if (!envelope.optBoolean("ok")) {
            throw PluginResultException(envelope.optString("error", "插件调用失败"))
        }
        return envelope.optJSONObject("value")
    }

    fun decodeContribution(envelopeJson: String, maxSkipIndex: Int): PromptContribution {
        val value = requireSuccess(envelopeJson) ?: return PromptContribution.EMPTY
        var totalChars = 0

        fun pieces(key: String): List<ExtPiece> {
            val array = value.optJSONArray(key) ?: return emptyList()
            return (0 until minOf(array.length(), MAX_PIECES)).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val content = item.optString("content").take(MAX_PIECE_CHARS)
                if (content.isBlank() || totalChars + content.length > MAX_TOTAL_RESULT_CHARS) {
                    return@mapNotNull null
                }
                totalChars += content.length
                ExtPiece(content = content, role = validRole(item.optString("role")))
            }
        }

        val preHistory = pieces("preHistory")
        val postHistory = pieces("postHistory")
        val depthInjections = value.optJSONArray("depthInjections")?.let { array ->
            (0 until minOf(array.length(), MAX_PIECES)).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val content = item.optString("content").take(MAX_PIECE_CHARS)
                if (content.isBlank() || totalChars + content.length > MAX_TOTAL_RESULT_CHARS) {
                    return@mapNotNull null
                }
                totalChars += content.length
                ExtDepthPiece(
                    content = content,
                    role = validRole(item.optString("role")),
                    depth = item.optInt("depth").coerceIn(0, MAX_DEPTH),
                )
            }
        }.orEmpty()

        return PromptContribution(
            preHistory = preHistory,
            postHistory = postHistory,
            depthInjections = depthInjections,
            skipMessagesUpTo = value.optInt("skipMessagesUpTo", -1).coerceIn(-1, maxSkipIndex.coerceAtLeast(-1)),
        )
    }

    private fun validRole(role: String): String = role.takeIf { it in ALLOWED_ROLES } ?: "system"

    private fun decodeState(raw: String): Any {
        if (raw.isBlank()) return JSONObject.NULL
        return runCatching { JSONTokener(raw).nextValue() }.getOrElse { raw }
    }

    class PluginResultException(message: String) : Exception(message)

    private const val MAX_MESSAGES = 80
    private const val MAX_MESSAGE_CHARS = 16_384
    private const val MAX_NAME_CHARS = 256
    private const val MAX_CONTEXT_CONTENT_BYTES = 96 * 1024
    private const val MAX_ARGUMENT_BYTES = 192 * 1024
    private const val MAX_STATE_BYTES = 128 * 1024
    private const val MAX_PIECES = 32
    private const val MAX_PIECE_CHARS = 16_384
    private const val MAX_TOTAL_RESULT_CHARS = 56 * 1024
    private const val MAX_DEPTH = 100
    private val ALLOWED_ROLES = setOf("system", "user", "assistant")
}
