package me.rerere.fawntavern.ui.chat

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.api.ApiRequestSnapshot
import me.rerere.fawntavern.domain.GenerationActionGuard
import me.rerere.fawntavern.ui.components.RenameDialog
import org.json.JSONArray
import org.json.JSONObject

/** Message menus, text panels and confirmations share one owner, outside the list layout. */
@Composable
internal fun ChatMessageOverlays(
    overlays: ChatOverlayState,
    state: ChatUiState,
    inputState: TextFieldState,
    media: ChatMediaActions,
    onAction: (ChatAction) -> Unit,
    onScrollToBottom: () -> Unit,
) {
    val conversation = state.conversation
    val profile = state.profile
    val generation = state.generation
    val resources = LocalResources.current
    with(overlays) {
        // ── 消息操作菜单 ──
        val menuTs = menuTarget?.message?.ts
        // 与渲染一致地取目标：overlay（流式/乐观态）优先，回退到内存会话
        val menuMsg = menuTarget?.message?.let { conversation.overlays[it.ts] ?: it }
        if (menuTs != null && menuMsg != null) {
            MessageMenu(
                onDismiss = { menuTarget = null },
                onSelectCopy = {
                    val sessionMessages = menuTarget?.messageWindow.orEmpty()
                    val messageIndex = sessionMessages.indexOfFirst { it.ts == menuMsg.ts }
                    val previewMessages = if (messageIndex >= 0) {
                        sessionMessages.toMutableList().also { it[messageIndex] = menuMsg }
                    } else {
                        sessionMessages + menuMsg
                    }
                    val previewIndex = previewMessages.indexOfFirst { it.ts == menuMsg.ts }
                    copyPanel = CopyPanel(
                        title = resources.getString(R.string.select_copy),
                        text = menuMsg.content,
                        preview = TextCopyPreview(
                            applyDisplayTransforms = menuMsg.role == "assistant",
                            regexScripts = if (menuMsg.role == "assistant") {
                                conversation.displayRegexScripts
                            } else {
                                emptyList()
                            },
                            depth = previewMessages.lastIndex - previewIndex,
                            userName = profile.userName,
                            charName = conversation.card?.name ?: conversation.current?.charName.orEmpty(),
                        ),
                        lineBatchSize = TEXT_PANEL_LINE_BATCH_SIZE,
                    )
                    menuTarget = null
                },
                onViewRequestBody = {
                    copyPanel = CopyPanel(
                        title = resources.getString(R.string.request_body),
                        text = formatRequestSnapshots(menuMsg.requestSnapshots),
                        lineBatchSize = TEXT_PANEL_LINE_BATCH_SIZE,
                    )
                    menuTarget = null
                },
                onEdit = {
                    onAction(ChatAction.StartEdit(menuMsg))
                    menuTarget = null
                },
                onDeleteCurrentVersion = {
                    val ts = menuTs
                    val wasLast = menuTarget?.wasLast == true
                    confirmDeleteCurrentVersion(state.settings.confirmDeleteCurrentVersion) {
                        onAction(ChatAction.DeleteMessage(ts))
                        if (wasLast) onScrollToBottom()
                    }
                    menuTarget = null
                },
                onDeleteAllVersions = {
                    val ts = menuTs
                    val wasLast = menuTarget?.wasLast == true
                    confirmDeleteAllVersions(state.settings.confirmDeleteAllVersions) {
                        onAction(ChatAction.DeleteAllVersions(ts))
                        if (wasLast) onScrollToBottom()
                    }
                    menuTarget = null
                },
                hasMultipleVersions = menuMsg.alts.size > 1,
                canViewRequestBody = menuMsg.role == "assistant" && menuMsg.requestSnapshots.isNotEmpty(),
            )
        }

        // ── 全屏底部面板：消息全文（只读）/ 输入框全文（可编辑，与输入框共用同一 TextFieldState）──
        copyPanel?.let { panel ->
            TextCopySheet(
                title = panel.title,
                text = if (panel.editable) inputState.text.toString() else panel.text,
                onCopyAll = { currentText ->
                    if (panel.editable) onAction(ChatAction.SetInputText(currentText))
                    media.copyText(currentText)
                    copyPanel = null
                },
                onSaveAsTxt = if (panel.preview != null) { currentText ->
                    media.saveText(currentText)
                    copyPanel = null
                } else null,
                onDismiss = { currentText ->
                    if (panel.editable) onAction(ChatAction.SetInputText(currentText))
                    copyPanel = null
                },
                editable = panel.editable,
                preview = panel.preview,
                lineBatchSize = panel.lineBatchSize,
            )
        }

        renameSession?.let { (id, title) ->
            RenameDialog(
                initialName = title,
                label = stringResource(R.string.chat_title_label),
                onConfirm = { newTitle ->
                    if (newTitle.isNotBlank()) {
                        onAction(ChatAction.RenameSession(id, newTitle))
                        renameSession = null
                    }
                },
                onDismiss = { renameSession = null },
            )
        }

        ChatConfirmationDialogs(
            showDeleteSession = deleteSessionId != null,
            deleteSessionEnabled = GenerationActionGuard.allowsMutation(generation.running),
            onDeleteSession = {
                deleteSessionId?.let { onAction(ChatAction.DeleteSession(it)) }
                deleteSessionId = null
            },
            onDismissDeleteSession = { deleteSessionId = null },
            showRegenerate = pendingRegenerate != null,
            onRegenerate = {
                val action = pendingRegenerate
                pendingRegenerate = null
                action?.invoke()
            },
            onDismissRegenerate = { pendingRegenerate = null },
            showDeleteCurrentVersion = pendingDeleteCurrentVersion != null,
            onDeleteCurrentVersion = {
                val action = pendingDeleteCurrentVersion
                pendingDeleteCurrentVersion = null
                action?.invoke()
            },
            onDismissDeleteCurrentVersion = { pendingDeleteCurrentVersion = null },
            showDeleteAllVersions = pendingDeleteAllVersions != null,
            onDeleteAllVersions = {
                val action = pendingDeleteAllVersions
                pendingDeleteAllVersions = null
                action?.invoke()
            },
            onDismissDeleteAllVersions = { pendingDeleteAllVersions = null },
        )

    }
}

/** 全屏底部面板的内容（消息全文 / 输入框全文共用同一面板）；editable = 输入框展开，正文可直接编辑 */
internal data class CopyPanel(
    val title: String,
    val text: String,
    val editable: Boolean = false,
    val preview: TextCopyPreview? = null,
    val lineBatchSize: Int? = null,
)

internal const val TEXT_PANEL_LINE_BATCH_SIZE = 100

internal fun formatRequestSnapshots(snapshots: List<ApiRequestSnapshot>): String {
    fun parsedBody(body: String): Any = runCatching {
        when (body.trimStart().firstOrNull()) {
            '{' -> JSONObject(body)
            '[' -> JSONArray(body)
            else -> body
        }
    }.getOrDefault(body)

    fun detail(index: Int, snapshot: ApiRequestSnapshot) = JSONObject().apply {
        if (snapshots.size > 1) put("round", index + 1)
        put("endpoint", snapshot.endpoint)
        put("body", parsedBody(snapshot.body))
    }

    val formatted = if (snapshots.size == 1) {
        detail(0, snapshots.single()).toString(2)
    } else {
        JSONArray().apply {
            snapshots.forEachIndexed { index, snapshot -> put(detail(index, snapshot)) }
        }.toString(2)
    }
    // Android JSONObject 会把 URL 的斜杠写成 `\/`；详情页只做显示还原，不改变存储内容。
    return formatted.replace("\\/", "/")
}
