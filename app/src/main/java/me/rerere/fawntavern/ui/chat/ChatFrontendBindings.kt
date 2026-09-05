package me.rerere.fawntavern.ui.chat

import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.chat.MsgAlt
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** One ID mapping is shared by serialization, context and incoming frontend actions. */
internal class ChatFrontendBindings(
    private val conversation: ChatConversationState,
    private val profile: ChatProfileState,
    private val messages: List<ChatMessage>,
    private val messageIds: Map<Long, Int>,
    val messagesJson: String,
    val localVariablesJson: String,
    val globalVariablesJson: String,
    private val userAvatar: String,
    private val characterAvatar: String,
    private val onAction: (ChatAction) -> Unit,
) {
    private val messagesById = messages.associateBy { messageIds.getValue(it.ts) }

    private fun resolve(id: Int): ChatMessage? =
        if (id < 0) messages.getOrNull(messages.size + id) else messagesById[id]

    fun updateMessage(id: Int, value: String) {
        resolve(id)?.let { onAction(ChatAction.UpdateMessage(it, value)) }
    }

    fun selectAlternative(id: Int, alternative: Int) {
        val message = resolve(id) ?: return
        if (alternative in message.alts.indices) {
            onAction(ChatAction.SwitchAlternative(message, alternative - message.altIdx))
        }
    }

    fun contextJson(msg: ChatMessage): String = JSONObject().apply {
        val messageId = messageIds[msg.ts] ?: -1
        put("chatId", conversation.current?.id.orEmpty())
        put("characterId", conversation.current?.charFile.orEmpty())
        put("characterFile", conversation.current?.charFile.orEmpty())
        put("presetId", conversation.card?.linkedPresetId.orEmpty())
        put("characterName", conversation.card?.name ?: conversation.current?.charName.orEmpty())
        conversation.card?.let { card ->
            put("character", JSONObject().apply {
                put("name", card.name)
                put("description", card.description)
                put("personality", card.personality)
                put("scenario", card.scenario)
                put("first_mes", card.firstMes)
                put("mes_example", card.mesExample)
                put("creator_notes", card.creatorNotes)
            })
        }
        put("userName", profile.userName)
        put("userAvatarPath", userAvatar)
        put("charAvatarPath", characterAvatar)
        put("messageId", messageId)
        put("lastMessageId", messages.lastOrNull()?.let { messageIds[it.ts] } ?: -1)
    }.toString()
}

@Composable
internal fun ChatFrontendEvents(events: Flow<ChatFrontendEvent>) {
    LaunchedEffect(events) { events.collect(::dispatchFrontendEvent) }
}

@Composable
internal fun rememberChatFrontendBindings(
    state: ChatUiState,
    messages: List<ChatMessage>,
    persistedIndexes: Map<Long, Int>,
    onAction: (ChatAction) -> Unit,
): ChatFrontendBindings {
    val conversation = state.conversation
    val profile = state.profile
    val inMemoryCount = conversation.current?.messages.orEmpty().size
    val messageIds = remember(messages, persistedIndexes, inMemoryCount) {
        messages.mapIndexed { index, message ->
            message.ts to (persistedIndexes[message.ts] ?: (inMemoryCount + index))
        }.toMap()
    }
    val messagesJson = remember(
        messages, messageIds, profile.userName, conversation.current?.charName, state.settings.javascriptSupport,
    ) {
        if (state.settings.javascriptSupport) {
            encodeChatFrontendMessages(messages, messageIds, profile.userName, conversation.current?.charName.orEmpty())
        } else "[]"
    }
    val local = remember(conversation.current?.localVariables) {
        encodeFrontendVariables(conversation.current?.localVariables.orEmpty())
    }
    val global = remember(state.globalVariables) { encodeFrontendVariables(state.globalVariables) }
    val userAvatar = remember(profile.userAvatar) { profile.userAvatar.toFrontendDataUrl() }
    val characterAvatar = remember(conversation.characterImage) { conversation.characterImage.toFrontendDataUrl() }
    return ChatFrontendBindings(
        conversation, profile, messages, messageIds, messagesJson, local, global, userAvatar, characterAvatar, onAction,
    )
}

internal fun encodeChatFrontendMessages(
    messages: List<ChatMessage>,
    messageIds: Map<Long, Int>,
    userName: String,
    characterName: String,
): String = JSONArray().apply {
    messages.forEach { message ->
        val messageId = messageIds.getValue(message.ts)
        val alternatives = message.alts.ifEmpty {
            listOf(MsgAlt(content = message.content, dataJson = message.dataJson))
        }
        put(JSONObject().apply {
            put("message_id", messageId)
            put("name", if (message.role == "user") userName else characterName)
            put("role", message.role)
            put("is_hidden", message.isHidden)
            put("message", message.content)
            put("swipe_id", message.altIdx)
            put("swipes", JSONArray().apply {
                alternatives.forEach { put(it.content) }
            })
            put("swipes_data", JSONArray().apply {
                alternatives.forEach { alternative ->
                    put(runCatching { JSONObject(alternative.dataJson) }.getOrElse { JSONObject() })
                }
            })
            put("swipes_info", JSONArray().apply {
                val count = message.alts.size.coerceAtLeast(1)
                repeat(count) { put(JSONObject()) }
            })
            put("data", runCatching { JSONObject(message.dataJson) }.getOrElse { JSONObject() })
            put("extra", JSONObject())
        })
    }
}.toString()

private fun Bitmap?.toFrontendDataUrl(): String {
    val bitmap = this ?: return ""
    return runCatching {
        val bytes = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
        "data:image/png;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
    }.getOrDefault("")
}
