package me.rerere.fawntavern.data.chat

/** 应用私有目录中已持久化的生成图片。 */
data class PersistedGeneratedImage(
    val path: String,
    val aspectRatio: String?,
)
