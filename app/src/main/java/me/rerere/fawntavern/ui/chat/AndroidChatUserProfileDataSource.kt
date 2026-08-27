package me.rerere.fawntavern.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import me.rerere.fawntavern.data.settings.UserAvatarStore
import me.rerere.fawntavern.data.settings.UserProfileStore

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
