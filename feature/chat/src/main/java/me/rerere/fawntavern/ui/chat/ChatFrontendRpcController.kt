package me.rerere.fawntavern.ui.chat

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.chat.ChatSession
import me.rerere.fawntavern.data.chat.MessageAlternatives
import me.rerere.fawntavern.data.chat.MsgAlt
import me.rerere.fawntavern.domain.chat.ChatDataRepository
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** 将前端卡 RPC 限定在当前会话，并串行化同会话内的批量写入。 */
internal class ChatFrontendRpcController(
    private val repository: ChatDataRepository,
    private val currentSession: () -> ChatSession?,
    private val replaceCurrent: (ChatSession) -> Unit,
    private val loadGlobalVariables: () -> Map<String, String>,
    private val saveGlobalVariables: suspend (Map<String, String>) -> Unit,
    private val scopedVariables: ChatFrontendVariableDataSource,
    private val scopeOwner: (scope: String, params: JSONObject) -> String,
    private val emitEvent: (type: String, payloadJson: String) -> Unit,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun call(method: String, paramsJson: String): String {
        val params = runCatching { JSONObject(paramsJson) }.getOrElse { JSONObject() }
        return when (method) {
            "runtime.ping" -> JSONObject().put("ok", true).put("apiVersion", 2).toString()
            "event.emit" -> {
                val type = params.optString("type")
                require(type.matches(Regex("^[a-z][a-z0-9_.-]{0,79}$"))) { "Invalid frontend event type" }
                val raw = params.opt("payload")
                val payload = when {
                    raw == null || raw === JSONObject.NULL -> "{}"
                    raw is JSONObject || raw is JSONArray -> raw.toString()
                    else -> JSONObject().put("value", raw).toString()
                }
                emitEvent(type, payload)
                "null"
            }
            "slash.run" -> runSlash(params)
            "chat.get-messages" -> withSession { session -> getMessages(session, params) }
            "chat.set-messages" -> mutate { session -> setMessages(session, params) }
                .also { emitEvent("message_edited", params.toString()) }
            "chat.delete-messages" -> mutate { session -> deleteMessages(session, params) }
                .also { emitEvent("message_deleted", params.toString()) }
            "chat.create-messages" -> mutate { session -> createMessages(session, params) }
                .also { emitEvent("message_received", JSONObject().put("message_ids", JSONArray(it)).toString()) }
            "chat.rotate-messages" -> mutate { session -> rotateMessages(session, params) }
                .also { emitEvent("message_edited", params.toString()) }
            "variables.get" -> getVariables(params)
            "variables.replace" -> replaceVariables(params)
                .also { emitEvent("variable_updated", JSONObject().put("scope", params.optString("scope", "chat")).toString()) }
            else -> error("Unknown frontend RPC method: $method")
        }
    }

    private suspend fun <T> withSession(block: suspend (ChatSession) -> T): T {
        val selected = currentSession() ?: error("No active chat")
        val latest = repository.get(selected.id) ?: selected
        return block(latest)
    }

    private suspend fun mutate(transform: (ChatSession) -> Pair<ChatSession, String>): String = withSession { latest ->
        locks.getOrPut(latest.id) { Mutex() }.withLock {
            val fresh = repository.get(latest.id) ?: latest
            val (updated, result) = transform(fresh)
            repository.save(updated)
            replaceCurrent(updated)
            result
        }
    }

    private fun setMessages(session: ChatSession, params: JSONObject): Pair<ChatSession, String> {
        val patches = params.optJSONArray("messages") ?: JSONArray()
        val messages = session.messages.toMutableList()
        for (i in 0 until patches.length()) {
            val patch = patches.optJSONObject(i) ?: continue
            val index = resolveIndex(patch.optInt("message_id", Int.MIN_VALUE), messages.size) ?: continue
            var message = messages[index]
            if (patch.has("is_hidden")) message = message.copy(isHidden = patch.optBoolean("is_hidden"))

            val swipes = patch.optJSONArray("swipes")
            if (swipes != null && swipes.length() > 0) {
                val old = message.alts.ifEmpty { listOf(message.toAlternative()) }
                val swipeData = patch.optJSONArray("swipes_data")
                val alternatives = (0 until swipes.length()).map { swipeIndex ->
                    old.getOrNull(swipeIndex).orEmptyAlternative().copy(
                        content = swipes.optString(swipeIndex),
                        dataJson = swipeData?.optJSONObject(swipeIndex)?.toString()
                            ?: old.getOrNull(swipeIndex)?.dataJson.orEmpty().ifBlank { "{}" },
                    )
                }
                val selected = patch.optInt("swipe_id", message.altIdx).coerceIn(0, alternatives.lastIndex)
                message = message.fromAlternative(alternatives[selected]).copy(
                    alts = if (alternatives.size > 1) alternatives else emptyList(),
                    altIdx = if (alternatives.size > 1) selected else 0,
                )
            } else if (patch.has("swipe_id") && message.alts.isNotEmpty()) {
                val selected = patch.optInt("swipe_id").coerceIn(0, message.alts.lastIndex)
                message = MessageAlternatives.switch(message, selected - message.altIdx) ?: message
            }
            if (patch.has("message")) message = message.copy(content = patch.optString("message"))
            patch.optJSONObject("data")?.let { message = message.copy(dataJson = it.toString()) }
            message = message.syncCurrentAlternative()
            messages[index] = message
        }
        return session.copy(messages = messages, updatedAt = System.currentTimeMillis()) to "null"
    }

    private fun runSlash(params: JSONObject): String {
        val command = params.optString("command").trim()
        if (!command.startsWith('/')) return command
        val name = command.drop(1).substringBefore(' ').lowercase()
        val value = command.drop(1).substringAfter(' ', "").trim()
        return when (name) {
            "event" -> {
                val event = value.substringBefore(' ').trim()
                require(event.matches(Regex("^[a-z][a-z0-9_.-]{0,79}$"))) { "Invalid slash event" }
                val payload = value.substringAfter(' ', "").trim().ifBlank { "{}" }
                emitEvent(event, payload)
                ""
            }
            "input" -> JSONObject().put("set_input", value).toString()
            else -> error("Unsupported slash command: /$name")
        }
    }

    private fun deleteMessages(session: ChatSession, params: JSONObject): Pair<ChatSession, String> {
        val ids = params.optJSONArray("message_ids") ?: JSONArray()
        val targets = (0 until ids.length()).mapNotNull { resolveIndex(ids.optInt(it), session.messages.size) }.toSet()
        val messages = session.messages.filterIndexed { index, _ -> index !in targets }
        return session.copy(messages = messages, updatedAt = System.currentTimeMillis()) to "null"
    }

    private fun getMessages(session: ChatSession, params: JSONObject): String {
        val total = session.messages.size
        val requestedOffset = params.optInt("offset", 0)
        val offset = if (requestedOffset < 0) total + requestedOffset else requestedOffset
        val start = offset.coerceIn(0, total)
        val limit = params.optInt("limit", 60).coerceIn(1, 120)
        val end = (start + limit).coerceAtMost(total)
        return JSONObject()
            .put("messages", encodeMessages(session.messages.subList(start, end), start))
            .put("offset", start)
            .put("total", total)
            .toString()
    }

    private fun createMessages(session: ChatSession, params: JSONObject): Pair<ChatSession, String> {
        val input = params.optJSONArray("messages") ?: JSONArray()
        val original = session.messages
        val messages = original.toMutableList()
        val insertBefore = params.optInt("insert_before", messages.size).let { raw ->
            if (raw < 0) (messages.size + raw).coerceIn(0, messages.size) else raw.coerceIn(0, messages.size)
        }
        val created = mutableListOf<ChatMessage>()
        val createdIds = JSONArray()
        repeat(input.length()) { index ->
            val item = input.optJSONObject(index) ?: return@repeat
            created += ChatMessage(
                role = item.optString("role").takeIf { it in setOf("system", "assistant", "user") } ?: "assistant",
                content = item.optString("message"),
                dataJson = item.optJSONObject("data")?.toString() ?: "{}",
                isHidden = item.optBoolean("is_hidden", false),
                ts = 0L,
            )
        }
        messages.addAll(insertBefore, created)
        val timestamps = original.map(ChatMessage::ts).toMutableList()
        var nextTs = maxOf(System.currentTimeMillis(), (timestamps.maxOrNull() ?: 0L) + 1L)
        repeat(created.size) { timestamps += nextTs++ }
        timestamps.sort()
        messages.indices.forEach { index -> messages[index] = messages[index].copy(ts = timestamps[index]) }
        repeat(created.size) { createdIds.put(insertBefore + it) }
        return session.copy(messages = messages, updatedAt = System.currentTimeMillis()) to createdIds.toString()
    }

    private fun rotateMessages(session: ChatSession, params: JSONObject): Pair<ChatSession, String> {
        val size = session.messages.size
        fun edge(name: String, fallback: Int): Int {
            val raw = params.optInt(name, fallback)
            return (if (raw < 0) size + raw else raw).coerceIn(0, size)
        }
        val begin = edge("begin", 0)
        val end = edge("end", size).coerceAtLeast(begin)
        val middle = edge("middle", begin).coerceIn(begin, end)
        if (begin == middle || middle == end) return session to "null"
        val reordered = session.messages.toMutableList()
        val right = reordered.subList(middle, end).toList()
        repeat(end - middle) { reordered.removeAt(middle) }
        reordered.addAll(begin, right)
        val timestamps = session.messages.map(ChatMessage::ts).sorted()
        val normalized = reordered.mapIndexed { index, message -> message.copy(ts = timestamps[index]) }
        return session.copy(messages = normalized, updatedAt = System.currentTimeMillis()) to "null"
    }

    private suspend fun getVariables(params: JSONObject): String {
        return when (params.optString("scope", "chat")) {
            "global" -> encodeVariables(loadGlobalVariables()).toString()
            "message" -> withSession { session ->
                val index = resolveIndex(params.optInt("message_id", -1), session.messages.size)
                index?.let { session.messages[it].dataJson }.orEmpty().ifBlank { "{}" }
            }
            "chat" -> withSession { encodeVariables(it.localVariables).toString() }
            "character", "preset", "script" -> {
                val scope = params.optString("scope")
                scopedVariables.load(scope, scopeOwner(scope, params))
            }
            else -> error("Variable scope is unavailable")
        }
    }

    private suspend fun replaceVariables(params: JSONObject): String {
        val value = params.optJSONObject("value") ?: JSONObject()
        return when (params.optString("scope", "chat")) {
            "global" -> {
                saveGlobalVariables(decodeVariables(value))
                "null"
            }
            "message" -> mutate { session ->
                val index = resolveIndex(params.optInt("message_id", -1), session.messages.size)
                    ?: return@mutate session to "null"
                val messages = session.messages.toMutableList()
                messages[index] = messages[index].copy(dataJson = value.toString()).syncCurrentAlternative()
                session.copy(messages = messages, updatedAt = System.currentTimeMillis()) to "null"
            }
            "chat" -> mutate { session ->
                session.copy(
                    localVariables = decodeVariables(value),
                    updatedAt = System.currentTimeMillis(),
                ) to "null"
            }
            "character", "preset", "script" -> {
                val scope = params.optString("scope")
                scopedVariables.save(scope, scopeOwner(scope, params), value.toString())
                "null"
            }
            else -> error("Variable scope is unavailable")
        }
    }

    private fun resolveIndex(raw: Int, size: Int): Int? {
        val index = if (raw < 0) size + raw else raw
        return index.takeIf { it in 0 until size }
    }

    private fun encodeMessages(messages: List<ChatMessage>, messageIdOffset: Int = 0) = JSONArray().apply {
        messages.forEachIndexed { index, message ->
            val alternatives = message.alts.ifEmpty { listOf(message.toAlternative()) }
            put(JSONObject().apply {
                put("message_id", messageIdOffset + index)
                put("role", message.role)
                put("message", message.content)
                put("data", message.dataObject())
                put("extra", JSONObject())
                put("is_hidden", message.isHidden)
                put("swipe_id", message.altIdx)
                put("swipes", JSONArray(alternatives.map(MsgAlt::content)))
                put("swipes_data", JSONArray(alternatives.map { it.dataObject() }))
                put("swipes_info", JSONArray(alternatives.map { JSONObject() }))
            })
        }
    }

    private fun encodeVariables(values: Map<String, String>) = JSONObject().apply {
        values.forEach { (key, raw) -> put(key, runCatching { JSONTokener(raw).nextValue() }.getOrElse { raw }) }
    }

    private fun decodeVariables(value: JSONObject): Map<String, String> = buildMap {
        value.keys().forEach { key ->
            val item = value.opt(key)
            put(key, if (item == null || item === JSONObject.NULL) "null" else item.toString())
        }
    }

    private fun ChatMessage.dataObject() = runCatching { JSONObject(dataJson) }.getOrElse { JSONObject() }
    private fun MsgAlt.dataObject() = runCatching { JSONObject(dataJson) }.getOrElse { JSONObject() }
    private fun MsgAlt?.orEmptyAlternative() = this ?: MsgAlt()

    private fun ChatMessage.toAlternative() = MsgAlt(
        content = content,
        dataJson = dataJson,
        reasoning = reasoning,
        model = model,
        reasoningMs = reasoningMs,
        searches = searches,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        cachedTokens = cachedTokens,
        generationMs = generationMs,
        images = images,
        imageAspectRatio = imageAspectRatio,
        requestSnapshots = requestSnapshots,
    )

    private fun ChatMessage.fromAlternative(value: MsgAlt) = copy(
        content = value.content,
        dataJson = value.dataJson,
        reasoning = value.reasoning,
        model = value.model,
        reasoningMs = value.reasoningMs,
        searches = value.searches,
        promptTokens = value.promptTokens,
        completionTokens = value.completionTokens,
        cachedTokens = value.cachedTokens,
        generationMs = value.generationMs,
        images = value.images,
        imageAspectRatio = value.imageAspectRatio,
        requestSnapshots = value.requestSnapshots,
    )

    private fun ChatMessage.syncCurrentAlternative(): ChatMessage {
        if (alts.isEmpty()) return this
        val normalized = altIdx.coerceIn(0, alts.lastIndex)
        val updated = alts.toMutableList()
        updated[normalized] = toAlternative()
        return copy(alts = updated, altIdx = normalized)
    }
}
