package me.rerere.stapp.domain

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.stapp.data.api.ApiProvider
import me.rerere.stapp.data.api.ChatApi
import me.rerere.stapp.data.chat.ChatSession
import me.rerere.stapp.data.chat.ChatMessage

/**
 * 一次流式生成的执行器：在 base 会话上追加（或在 regenIdx 指定的 assistant 消息上开新版本）AI 回复，
 * 流式期间以约 60ms 一帧的节流频率通过 onUpdate 发布中间会话，返回最终会话。
 * 不做 IO 落盘、不持有 UI 状态 —— 调用方负责保存与 generating 标志。
 */
internal class GenerationController {

    private val stopFlag = AtomicBoolean(false)

    /** 用户主动停止当前生成 */
    fun stop() {
        stopFlag.set(true)
    }

    suspend fun run(
        base: ChatSession,
        provider: ApiProvider,
        modelId: String,
        built: PromptBuilder.Built,
        filesDir: java.io.File?,
        streaming: Boolean,
        regenIdx: Int?,
        errorText: (Exception) -> String,
        onUpdate: (ChatSession) -> Unit,
    ): ChatSession = coroutineScope {
        stopFlag.set(false)
        // regenIdx != null：在该下标的 assistant 消息上开新版本重答（其后的消息保留）；否则追加新消息
        var cur = if (regenIdx != null) {
            ConversationOps.startVariant(base, regenIdx, modelId)
        } else {
            base.copy(
                messages = base.messages + ChatMessage(
                    role = "assistant", model = modelId, ts = ConversationOps.nextTs(base)),
                updatedAt = System.currentTimeMillis(),
            )
        }
        val idx = regenIdx ?: cur.messages.lastIndex
        onUpdate(cur)
        var reasoningStart = 0L
        var reasoningEnd = 0L  // 正文首个 token 到达时定格思考耗时，避免"思考了 xx 秒"在正文输出期间持续增长
        // 累加缓冲：SSE 在 IO 线程高频回调，UI 按帧节流刷新，避免每个 token 都重组。
        // lock 保护 StringBuilder：IO 线程 append 与主线程 toString 并发
        val lock = Any()
        val buf = StringBuilder()
        val reasoningBuf = StringBuilder()
        val dirty = AtomicBoolean(false)
        fun flushToUi() {
            val (content, reasoning) = synchronized(lock) { buf.toString() to reasoningBuf.toString() }
            val reasoningMs = when {
                reasoningStart == 0L -> 0L
                reasoningEnd != 0L -> reasoningEnd - reasoningStart
                else -> System.currentTimeMillis() - reasoningStart
            }
            val list = cur.messages.toMutableList()
            val m = list[idx]
            list[idx] = m.copy(content = content, reasoning = reasoning, reasoningMs = reasoningMs)
            cur = cur.copy(messages = list)
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
            val apiMessages = PromptBuilder.assemble(built, cur.messages.subList(0, idx), filesDir)
            // 记录本次组装出的完整 prompt（日志开关关闭时为空操作）
            PromptLog.record(built, provider, modelId, apiMessages)
            ChatApi.streamChat(
                provider = provider,
                modelId = modelId,
                messages = apiMessages,
                params = built.genParams,
                isCancelled = { stopFlag.get() },
            ) { c, r ->
                if (stopFlag.get()) throw ChatApi.Stopped()
                if (r.isNotEmpty() && reasoningStart == 0L) reasoningStart = System.currentTimeMillis()
                if (c.isNotEmpty() && reasoningStart != 0L && reasoningEnd == 0L) reasoningEnd = System.currentTimeMillis()
                synchronized(lock) { buf.append(c); reasoningBuf.append(r) }
                dirty.set(true)
            }
        } catch (_: ChatApi.Stopped) {
        } catch (e: Exception) {
            synchronized(lock) {
                if (buf.isNotEmpty()) buf.append("\n\n")
                buf.append(errorText(e))
            }
        }
        refresher?.cancel()
        flushToUi()  // 收尾：把缓冲的最终内容全部写入
        // 将最终内容同步回当前版本
        run {
            val list = cur.messages.toMutableList()
            val m = list[idx]
            if (m.alts.isNotEmpty()) {
                val alts = m.alts.toMutableList()
                alts[m.altIdx] = alts[m.altIdx].copy(
                    content = m.content, reasoning = m.reasoning,
                    model = m.model, reasoningMs = m.reasoningMs)
                list[idx] = m.copy(alts = alts)
                cur = cur.copy(messages = list)
            }
        }
        cur = cur.copy(updatedAt = System.currentTimeMillis())
        onUpdate(cur)
        cur
    }
}
