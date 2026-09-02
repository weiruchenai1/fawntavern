package me.rerere.fawntavern.domain

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.fawntavern.data.api.ApiMessage
import me.rerere.fawntavern.data.api.GeneratedImage
import me.rerere.fawntavern.data.api.ToolSpec
import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.chat.MsgSearch
import me.rerere.fawntavern.data.chat.PersistedGeneratedImage

/**
 * 一次流式生成的执行器：把 AI 回复流式填充进 [run] 传入的目标消息 [genMessage]（可为追加的新
 * 空消息，或已开好新版本的重答消息），流式期间以约 60ms 一帧的节流频率通过 onUpdate 发布中间消息，
 * 返回最终消息。不做 IO 落盘、不持有 UI 状态 —— 调用方负责保存与 generating 标志。
 *
 * 支持函数工具多轮循环（联网搜索等）：模型发起工具调用 → [GenerationToolExecutor] 执行 →
 * 结果以工具消息回传 → 续下一轮流式，直到模型不再调用工具。
 */
class GenerationEngine(
    private val gateway: GenerationGateway,
) {
    private companion object {
        /** 正常执行的工具轮数上限；超过后拒绝执行并让模型直接作答 */
        const val MAX_TOOL_ROUNDS = 3
        /** 硬性轮数上限：连拒绝执行的引导轮都用完时直接收尾，杜绝无限循环 */
        const val HARD_TOOL_ROUNDS = 5
    }

    private val stopFlag = AtomicBoolean(false)

    /** 用户主动停止当前生成 */
    fun stop() {
        stopFlag.set(true)
    }

    suspend fun run(
        apiMessages: List<ApiMessage>,
        genMessage: ChatMessage,
        providerId: String,
        providerName: String,
        modelId: String,
        built: PromptBuilder.Built,
        streaming: Boolean,
        tools: List<ToolSpec> = emptyList(),
        toolExecutor: GenerationToolExecutor? = null,
        persistGeneratedImage: suspend (GeneratedImage) -> PersistedGeneratedImage? = { null },
        errorText: (Exception) -> String,
        onUpdate: (ChatMessage) -> Unit,
    ): ChatMessage = coroutineScope {
        stopFlag.set(false)
        val generationStartedAt = System.currentTimeMillis()
        var cur = genMessage
        onUpdate(cur)
        // 思考耗时分段累计：思考 → 工具调用/正文时冻结一段，下一轮再有思考则重开一段。
        // 搜索执行期间的时间不算进思考耗时
        var reasoningStart = 0L
        var reasoningEnd = 0L
        var reasoningAccum = 0L
        // 搜索时间线步骤（工具循环期间追加/更新），随每帧刷进 cur
        var searches = genMessage.searches
        var generatedImages = genMessage.images
        var requestSnapshots = genMessage.requestSnapshots
        // 累加缓冲：SSE 在 IO 线程高频回调，UI 按帧节流刷新，避免每个 token 都重组。
        // lock 保护 StringBuilder：IO 线程 append 与主线程 toString 并发
        val lock = Any()
        val buf = StringBuilder()
        val reasoningBuf = StringBuilder()
        val dirty = AtomicBoolean(false)
        fun reasoningMsNow(): Long {
            val segment = when {
                reasoningStart == 0L -> 0L
                reasoningEnd != 0L -> reasoningEnd - reasoningStart
                else -> System.currentTimeMillis() - reasoningStart
            }
            return reasoningAccum + segment
        }
        fun flushToUi() {
            val (content, reasoning) = synchronized(lock) { buf.toString() to reasoningBuf.toString() }
            cur = cur.copy(content = content, reasoning = reasoning,
                reasoningMs = reasoningMsNow(), searches = searches)
            onUpdate(cur)
        }
        // 流式模式下的节流刷新协程（约每 60ms 一帧），由 refresher.cancel() 终止
        val refresher = if (streaming) launch {
            while (true) {
                if (dirty.getAndSet(false)) flushToUi()
                delay(60)
            }
        } else null
        try {
            val apiMessages = apiMessages.toMutableList()
            fun estimatePromptTokens(): Int = apiMessages.sumOf { message ->
                TokenEstimator.estimate(message.role) + TokenEstimator.estimate(message.content) +
                    message.toolCalls.sumOf { call ->
                        TokenEstimator.estimate(call.name) + TokenEstimator.estimate(call.arguments) +
                            TokenEstimator.estimate(call.result)
                    } + message.images.size * 256
            }
            var promptTokens = 0
            var completionTokens = 0
            var cachedTokens = 0
            var countedContentChars = 0
            var countedReasoningChars = 0
            // 记录本次组装出的完整 prompt（日志开关关闭时为空操作）
            PromptLog.record(built, providerName, modelId, apiMessages)
            var rounds = 0
            while (true) {
                val roundStart = synchronized(lock) { buf.length }
                val reasoningRoundStart = synchronized(lock) { reasoningBuf.length }
                val estimatedInput = estimatePromptTokens()
                promptTokens += estimatedInput
                val end = gateway.stream(
                    request = GenerationStreamRequest(
                    providerId = providerId,
                    modelId = modelId,
                    messages = apiMessages,
                    params = built.genParams,
                    tools = tools,
                    isCancelled = { stopFlag.get() },
                    ),
                ) { event ->
                    if (stopFlag.get()) throw GenerationCancelled()
                    val c = (event as? GenerationEvent.ContentDelta)?.content.orEmpty()
                    val r = (event as? GenerationEvent.ReasoningDelta)?.reasoning.orEmpty()
                    if (r.isNotEmpty()) {
                        if (reasoningStart == 0L) {
                            reasoningStart = System.currentTimeMillis()
                        } else if (reasoningEnd != 0L) {
                            // 工具轮后的新一段思考：上一段沉淀进累计值，重开计时
                            reasoningAccum += reasoningEnd - reasoningStart
                            reasoningStart = System.currentTimeMillis()
                            reasoningEnd = 0L
                        }
                    }
                    if (c.isNotEmpty() && reasoningStart != 0L && reasoningEnd == 0L) {
                        reasoningEnd = System.currentTimeMillis()
                    }
                    synchronized(lock) { buf.append(c); reasoningBuf.append(r) }
                    dirty.set(true)
                }
                if (end.promptTokens > 0) promptTokens += end.promptTokens - estimatedInput
                end.requestSnapshot?.let { snapshot ->
                    requestSnapshots = requestSnapshots + snapshot
                    cur = cur.copy(requestSnapshots = requestSnapshots)
                    onUpdate(cur)
                }
                val (roundContent, roundReasoning) = synchronized(lock) {
                    buf.substring(roundStart) to reasoningBuf.substring(reasoningRoundStart)
                }
                val estimatedOutput = TokenEstimator.estimate(roundContent) +
                    TokenEstimator.estimate(roundReasoning)
                completionTokens += end.completionTokens.takeIf { it > 0 } ?: estimatedOutput
                cachedTokens += end.cachedTokens
                end.generatedImages.forEach { image ->
                    val persisted = persistGeneratedImage(image)
                        ?: throw IllegalStateException("Failed to save generated image")
                    generatedImages = generatedImages + persisted.path
                    cur = cur.copy(
                        images = generatedImages,
                        imageAspectRatio = if (cur.imageAspectRatio.equals("auto", ignoreCase = true)) {
                            persisted.aspectRatio ?: cur.imageAspectRatio
                        } else {
                            cur.imageAspectRatio
                        },
                    )
                    onUpdate(cur)
                }
                countedContentChars = synchronized(lock) { buf.length }
                countedReasoningChars = synchronized(lock) { reasoningBuf.length }
                if (end.toolCalls.isEmpty() || toolExecutor == null) break
                if (rounds >= HARD_TOOL_ROUNDS) break
                // 工具调用到达即冻结当前思考段（执行搜索的时间不计入思考耗时）
                if (reasoningStart != 0L && reasoningEnd == 0L) reasoningEnd = System.currentTimeMillis()
                val allowExec = rounds < MAX_TOOL_ROUNDS
                val executed = end.toolCalls.map { call ->
                    if (!allowExec) {
                        return@map call.copy(result =
                            """{"error":"Tool call limit reached. Answer directly based on the information you already have."}""")
                    }
                    // 时间线步骤盖上"发起时刻的思考进度"戳：UI 据此把思考切成搜索前后的分段
                    fun stamp(s: MsgSearch) = s.copy(
                        reasoningChars = synchronized(lock) { reasoningBuf.length },
                        reasoningMs = reasoningMsNow(),
                    )
                    val pending = toolExecutor.describe(call)?.let(::stamp)
                    if (pending != null) {
                        searches = searches + pending
                        flushToUi()
                    }
                    val (result, final) = try {
                        toolExecutor.execute(call)
                    } catch (e: Exception) {
                        if (e is GenerationCancelled) throw e
                        // 工具失败不打断生成：把错误回传给模型自行处理
                        org.json.JSONObject()
                            .put("error", e.message ?: "tool failed")
                            .toString() to
                            pending?.copy(searching = false)
                    }
                    if (pending != null) {
                        val done = final?.copy(
                            reasoningChars = pending.reasoningChars,
                            reasoningMs = pending.reasoningMs,
                        ) ?: pending.copy(searching = false)
                        searches = searches.dropLast(1) + done
                        flushToUi()
                    } else if (final != null) {
                        searches = searches + stamp(final)
                        flushToUi()
                    }
                    call.copy(result = result)
                }
                // 本轮 assistant 输出（正文 + 调用 + 协议私有原始块）连同工具结果拼回历史，续下一轮
                apiMessages += ApiMessage(
                    role = "assistant",
                    content = roundContent,
                    toolCalls = executed,
                    rawBlocks = end.rawBlocks,
                )
                rounds++
            }
            val (remainingContent, remainingReasoning) = synchronized(lock) {
                buf.substring(countedContentChars) to reasoningBuf.substring(countedReasoningChars)
            }
            completionTokens += TokenEstimator.estimate(remainingContent) +
                TokenEstimator.estimate(remainingReasoning)
            cur = cur.copy(
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                cachedTokens = cachedTokens,
                generationMs = (System.currentTimeMillis() - generationStartedAt).coerceAtLeast(1L),
                requestSnapshots = requestSnapshots,
            )
        } catch (_: GenerationCancelled) {
        } catch (e: Exception) {
            val displayError = if (e is GenerationRequestException) {
                if (e.snapshot !in requestSnapshots) {
                    requestSnapshots = requestSnapshots + e.snapshot
                    cur = cur.copy(requestSnapshots = requestSnapshots)
                }
                e.cause as? Exception ?: e
            } else {
                e
            }
            synchronized(lock) {
                if (buf.isNotEmpty()) buf.append("\n\n")
                buf.append(errorText(displayError))
            }
        }
        refresher?.cancel()
        // 收尾：搜索步骤一律离开"搜索中"状态（停止/异常路径可能残留）
        searches = searches.map { if (it.searching) it.copy(searching = false) else it }
        flushToUi()  // 把缓冲的最终内容全部写入
        if (cur.generationMs == 0L) {
            cur = cur.copy(
                completionTokens = TokenEstimator.estimate(cur.content) + TokenEstimator.estimate(cur.reasoning),
                generationMs = (System.currentTimeMillis() - generationStartedAt).coerceAtLeast(1L),
            )
        }
        // 将最终内容同步回当前版本（重答开的新版本）
        if (cur.alts.isNotEmpty()) {
            val alts = cur.alts.toMutableList()
            alts[cur.altIdx] = alts[cur.altIdx].copy(
                content = cur.content, dataJson = cur.dataJson, reasoning = cur.reasoning,
                model = cur.model, reasoningMs = cur.reasoningMs, searches = cur.searches,
                images = cur.images, imageAspectRatio = cur.imageAspectRatio,
                requestSnapshots = cur.requestSnapshots,
                promptTokens = cur.promptTokens, completionTokens = cur.completionTokens,
                cachedTokens = cur.cachedTokens,
                generationMs = cur.generationMs)
            cur = cur.copy(alts = alts)
        }
        onUpdate(cur)
        cur
    }
}
