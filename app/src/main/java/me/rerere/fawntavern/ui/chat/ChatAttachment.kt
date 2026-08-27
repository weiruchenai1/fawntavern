package me.rerere.fawntavern.ui.chat

import android.net.Uri

internal data class Attachment(
    val uri: Uri,
    val isImage: Boolean,
)
