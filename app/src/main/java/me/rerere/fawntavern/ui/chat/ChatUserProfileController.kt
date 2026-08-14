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
    fun loadName(): String
    fun load(): ChatUserProfile
    fun save(name: String, description: String)
    suspend fun saveAvatar(uri: Uri): Bitmap?
    suspend fun deleteAvatar()
}

internal class AndroidChatUserProfileDataSource(
    private val context: Context,
) : ChatUserProfileDataSource {
    override fun loadName(): String = UserProfileStore.getName(context)

    override fun load(): ChatUserProfile = ChatUserProfile(
        name = UserProfileStore.getName(context),
        description = UserProfileStore.getDescription(context),
        avatarColor = UserProfileStore.getAvatarColor(context),
        avatar = UserAvatarStore.load(context),
    )

    override fun save(name: String, description: String) {
        UserProfileStore.setName(context, name)
        UserProfileStore.setDescription(context, description)
    }

    override suspend fun saveAvatar(uri: Uri): Bitmap? = UserAvatarStore.save(context, uri)

    override suspend fun deleteAvatar() {
        UserAvatarStore.delete(context)
    }
}

internal class ChatUserProfileController(
    private val dataSource: ChatUserProfileDataSource,
) {
    fun loadName(): String = dataSource.loadName()
    fun load(): ChatUserProfile = dataSource.load()
    fun save(name: String, description: String) = dataSource.save(name.trim().ifBlank { "user" }, description)
    suspend fun saveAvatar(uri: Uri): Bitmap? = dataSource.saveAvatar(uri)
    suspend fun deleteAvatar() = dataSource.deleteAvatar()
}
