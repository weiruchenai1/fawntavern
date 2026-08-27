package me.rerere.fawntavern.ui.chat

import android.graphics.Bitmap
import android.net.Uri

data class ChatUserProfile(
    val name: String,
    val description: String,
    val avatarColor: Long,
    val avatar: Bitmap?,
)

interface ChatUserProfileDataSource {
    fun loadName(): String
    fun load(): ChatUserProfile
    fun save(name: String, description: String)
    suspend fun saveAvatar(uri: Uri): Bitmap?
    suspend fun deleteAvatar()
}

class ChatUserProfileController(
    private val dataSource: ChatUserProfileDataSource,
) {
    fun loadName(): String = dataSource.loadName()

    fun load(): ChatUserProfile = dataSource.load()

    fun save(name: String, description: String) =
        dataSource.save(name.trim().ifBlank { "user" }, description)

    suspend fun saveAvatar(uri: Uri): Bitmap? = dataSource.saveAvatar(uri)

    suspend fun deleteAvatar() = dataSource.deleteAvatar()
}
