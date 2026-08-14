package me.rerere.fawntavern.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import me.rerere.fawntavern.data.settings.UserAvatarStore
import me.rerere.fawntavern.data.settings.UserProfileStore

internal data class ChatUserProfile(
    val name: String,
    val description: String,
    val avatarColor: Long,
    val avatar: Bitmap?,
)

internal interface ChatUserProfileDataSource {
    fun load(): ChatUserProfile
    suspend fun saveAvatar(uri: Uri): Bitmap?
    suspend fun deleteAvatar()
}

internal class AndroidChatUserProfileDataSource(
    private val context: Context,
) : ChatUserProfileDataSource {
    override fun load(): ChatUserProfile = ChatUserProfile(
        name = UserProfileStore.getName(context),
        description = UserProfileStore.getDescription(context),
        avatarColor = UserProfileStore.getAvatarColor(context),
        avatar = UserAvatarStore.load(context),
    )

    override suspend fun saveAvatar(uri: Uri): Bitmap? = UserAvatarStore.save(context, uri)

    override suspend fun deleteAvatar() {
        UserAvatarStore.delete(context)
    }
}

internal class ChatUserProfileController(
    private val dataSource: ChatUserProfileDataSource,
) {
    fun load(): ChatUserProfile = dataSource.load()
    suspend fun saveAvatar(uri: Uri): Bitmap? = dataSource.saveAvatar(uri)
    suspend fun deleteAvatar() = dataSource.deleteAvatar()
}
