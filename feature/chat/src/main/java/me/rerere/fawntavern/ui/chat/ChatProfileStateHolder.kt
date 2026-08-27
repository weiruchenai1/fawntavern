package me.rerere.fawntavern.ui.chat

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import me.rerere.fawntavern.data.speech.TtsUiState

data class ChatProfileState(
    val userName: String,
    val userAvatar: Bitmap?,
    val speakingTimestamp: Long?,
    val tts: TtsUiState,
)

/** 持有聊天界面使用的用户资料快照。 */
class ChatProfileStateHolder(
    private val controller: ChatUserProfileController,
) {
    var name by mutableStateOf(controller.loadName())
        private set

    var avatar by mutableStateOf<Bitmap?>(null)
        private set

    fun load(): ChatUserProfile = controller.load()

    fun apply(profile: ChatUserProfile) {
        name = profile.name
        avatar = profile.avatar
    }

    fun save(name: String, description: String) {
        controller.save(name, description)
    }
}
