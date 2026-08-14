package me.rerere.fawntavern.extension.builtin

import me.rerere.fawntavern.data.api.ApiConfigStore
import me.rerere.fawntavern.data.api.ApiMessage
import me.rerere.fawntavern.data.settings.DefaultModelStore
import me.rerere.fawntavern.domain.PromptBuilder
import me.rerere.fawntavern.extension.ExtPiece
import me.rerere.fawntavern.extension.Extension
import me.rerere.fawntavern.extension.ExtensionInfo
import me.rerere.fawntavern.extension.ExtensionServices
import me.rerere.fawntavern.extension.HostServices
import me.rerere.fawntavern.extension.GenerationContext
import me.rerere.fawntavern.extension.GenerationLifecycle
import me.rerere.fawntavern.extension.PromptContext
import me.rerere.fawntavern.extension.PromptContribution
import me.rerere.fawntavern.extension.PromptContributor
import org.json.JSONObject

/**
 * 官方扩展：对话摘要/总结（滚动压缩）。
 *
 * - [contribute]（PromptContributor）：把已存摘要作为一个 system 块注入**历史之前**。这是"旁路注入"——
 *   不改动原消息、不进历史流，因此不会被 [PromptBuilder] 的 token 预算从新到旧裁掉；预算紧张时旧历史被裁、
 *   摘要仍在，正是所需。
 * - [onGenerationComplete]（GenerationLifecycle）：生成完成后，把 keepRecent 之外**新增**的历史（超过
 *   triggerTokens 才触发）连同旧摘要一起压成新的滚动摘要，写回会话级状态（水位 coveredUpTo 前进）。
 *
 * 注入方式为旁路（不重建历史，以兼容本项目 alts/ts 严格模型）。配置存 `ExtensionStore.getConfig(ID)`，
 * 会话级状态存 `ChatSession.extState[ID]`。
 */
object SummarizeExtension : Extension, PromptContributor, GenerationLifecycle {

    const val ID = "builtin.summarize"

    override val info = ExtensionInfo(
        id = ID,
        name = "Summarize",
        description = "把较早的对话滚动压缩成摘要并注入提示，缓解长对话超出上下文。",
        defaultEnabled = false,
    )

    // ── 会话级状态：当前摘要 + 已折叠到的消息水位（extState[ID]） ──
    private data class State(val summary: String = "", val coveredUpTo: Int = 0)

    private fun parseState(blob: String): State =
        if (blob.isBlank()) State() else try {
            val o = JSONObject(blob)
            State(o.optString("summary"), o.optInt("covered"))
        } catch (_: Exception) {
            State()
        }

    private fun encodeState(s: State): String =
        JSONObject().put("summary", s.summary).put("covered", s.coveredUpTo).toString()

    // ── 配置（ExtensionStore.getConfig(ID)），供设置界面读写 ──
    data class Config(
        val auto: Boolean = true,
        val keepRecent: Int = 20,       // 最近 N 条保留原样、不进摘要
        val targetTokens: Int = 800,    // 摘要目标长度
        val triggerTokens: Int = 2500,  // 待压缩历史超过此 token 才触发（省调用）
        val modelId: String = "",       // 摘要用模型 "providerId::modelId"（空 = 当前会话模型）
    )

    fun parseConfig(blob: String): Config =
        if (blob.isBlank()) Config() else try {
            val o = JSONObject(blob)
            val d = Config()
            Config(
                auto = o.optBoolean("auto", d.auto),
                keepRecent = o.optInt("keepRecent", d.keepRecent),
                targetTokens = o.optInt("targetTokens", d.targetTokens),
                triggerTokens = o.optInt("triggerTokens", d.triggerTokens),
                modelId = o.optString("modelId", d.modelId),
            )
        } catch (_: Exception) {
            Config()
        }

    fun encodeConfig(c: Config): String = JSONObject()
        .put("auto", c.auto)
        .put("keepRecent", c.keepRecent)
        .put("targetTokens", c.targetTokens)
        .put("triggerTokens", c.triggerTokens)
        .put("modelId", c.modelId)
        .toString()

    override fun contribute(ctx: PromptContext): PromptContribution {
        val state = parseState(ctx.extState)
        val summary = state.summary
        if (summary.isBlank()) return PromptContribution.EMPTY
        return PromptContribution(
            preHistory = listOf(ExtPiece("[对话摘要 / Summary of earlier conversation]\n$summary")),
            // 告诉系统：前 N 条消息已被压缩进摘要，构建 prompt 时可跳过原文
            skipMessagesUpTo = state.coveredUpTo,
        )
    }

    override suspend fun onGenerationComplete(ctx: GenerationContext, services: ExtensionServices) {
        val cfg = parseConfig(ctx.config)
        if (!cfg.auto) return
        val msgs = ctx.session.messages
        val state = parseState(ctx.extState)
        val end = msgs.size - cfg.keepRecent.coerceAtLeast(0)
        if (end <= state.coveredUpTo) return                     // 没有新的可折叠历史
        val chunk = msgs.subList(state.coveredUpTo, end).filter { it.content.isNotBlank() }
        if (chunk.isEmpty()) return
        if (chunk.sumOf { PromptBuilder.estTokens(it.content) } < cfg.triggerTokens.coerceAtLeast(1)) return

        val convo = chunk.joinToString("\n") { m ->
            val who = if (m.role == "assistant") ctx.charName.ifBlank { "Character" }
                      else ctx.userName.ifBlank { "User" }
            "$who: ${m.content}"
        }
        val userText = buildString {
            if (state.summary.isNotBlank()) append("Previous summary:\n").append(state.summary).append("\n\n")
            append("New conversation to fold in:\n").append(convo)
        }
        // 摘要模型：优先用 DefaultModelStore 的摘要角色，回退到扩展自身配置的 modelId
        val hostCtx = (services as? HostServices)?.ctx
        val resolvedModel = if (hostCtx != null)
            DefaultModelStore.get(hostCtx, DefaultModelStore.ROLE_SUMMARY).model.takeIf { it.isNotBlank() } ?: cfg.modelId
        else cfg.modelId
        val summary = try {
            services.callModel(
                messages = listOf(
                    ApiMessage("system", COMPRESS_SYSTEM.replace("{target}", cfg.targetTokens.toString())),
                    ApiMessage("user", userText),
                ),
                params = null,
                modelId = resolvedModel.ifBlank { null },
            ).trim()
        } catch (_: Exception) {
            return   // 摘要失败静默跳过，下次生成再试
        }
        if (summary.isBlank()) return
        services.saveExtState(ctx.session.id, ID, encodeState(State(summary = summary, coveredUpTo = end)))
    }

    private const val COMPRESS_SYSTEM =
        "You are a summarization engine for a roleplay chat. Merge the previous summary (if any) and the new " +
        "conversation into a single, updated summary. Preserve key facts, character state, relationships, ongoing " +
        "goals and unresolved threads; drop small talk. Write in the same language as the conversation. Aim for " +
        "about {target} tokens. Output only the summary text, with no preamble."
}
