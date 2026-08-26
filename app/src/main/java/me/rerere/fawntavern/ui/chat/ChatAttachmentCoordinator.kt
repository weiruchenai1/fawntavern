package me.rerere.fawntavern.ui.chat

import android.content.Context
import android.net.Uri
import me.rerere.fawntavern.data.chat.AttachmentStore
import me.rerere.fawntavern.data.chat.ChatDataRepository
import me.rerere.fawntavern.data.chat.MsgFile

internal data class PersistedAttachments(
    val images: List<String>,
    val files: List<MsgFile>,
)

internal interface ChatAttachmentDataSource {
    fun isTooLarge(uri: Uri): Boolean
    suspend fun persistImage(uri: Uri): String?
    suspend fun persistFile(uri: Uri): MsgFile?
    suspend fun collectUnused()
}

internal class AndroidChatAttachmentDataSource(
    private val context: Context,
    private val chatRepository: ChatDataRepository,
) : ChatAttachmentDataSource {
    override fun isTooLarge(uri: Uri): Boolean = AttachmentStore.isTooLarge(context, uri)
    override suspend fun persistImage(uri: Uri): String? = AttachmentStore.persistImage(context, uri)
    override suspend fun persistFile(uri: Uri): MsgFile? = AttachmentStore.persistFile(context, uri)
    override suspend fun collectUnused() = chatRepository.collectUnusedAttachments()
}

internal class ChatAttachmentCoordinator(
    private val dataSource: ChatAttachmentDataSource,
) {
    fun hasOversizedFile(attachments: List<Attachment>): Boolean =
        attachments.any { !it.isImage && dataSource.isTooLarge(it.uri) }

    suspend fun persist(attachments: List<Attachment>): PersistedAttachments? {
        val images = mutableListOf<String>()
        val files = mutableListOf<MsgFile>()
        for (attachment in attachments) {
            val success = if (attachment.isImage) {
                dataSource.persistImage(attachment.uri)?.also(images::add) != null
            } else {
                dataSource.persistFile(attachment.uri)?.also(files::add) != null
            }
            if (!success) {
                dataSource.collectUnused()
                return null
            }
        }
        return PersistedAttachments(images, files)
    }

    suspend fun collectUnused() = dataSource.collectUnused()
}
