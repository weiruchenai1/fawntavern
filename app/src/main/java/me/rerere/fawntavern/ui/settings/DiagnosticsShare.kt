package me.rerere.fawntavern.ui.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

internal fun shareDiagnosticsText(
    context: Context,
    fileName: String,
    content: String,
    subject: String,
    chooserTitle: String,
) {
    val shareDirectory = File(context.cacheDir, "shared-diagnostics").apply { mkdirs() }
    val reportFile = File(shareDirectory, fileName).apply { writeText(content) }
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        reportFile,
    )
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newUri(context.contentResolver, subject, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, chooserTitle))
}
