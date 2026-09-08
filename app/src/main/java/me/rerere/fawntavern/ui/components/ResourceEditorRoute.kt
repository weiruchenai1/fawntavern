package me.rerere.fawntavern.ui.components

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import kotlinx.coroutines.CancellationException
import me.rerere.fawntavern.R
import me.rerere.fawntavern.core.diagnostics.SafeLog

/** 路由只恢复条目名称，完整资源在界面恢复后重新加载。 */
@Composable
internal fun <T> ResourceEditorRoute(
    name: String,
    load: suspend (String) -> T,
    onBack: () -> Unit,
    content: @Composable (T) -> Unit,
) = key(name) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val loaded by produceState<Result<T>?>(null, name) {
        value = try {
            Result.success(load(name))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
    BackHandler(onBack = onBack)
    val result = loaded
    if (result == null) {
        LoadingState()
    } else if (result.isFailure) {
        LaunchedEffect(result, resources) {
            val error = result.exceptionOrNull()
            SafeLog.error("ResourceEditor", "resource_load_failed", error)
            Toast.makeText(context, resources.getString(R.string.editor_draft_failed_fmt, error?.message.orEmpty()), Toast.LENGTH_LONG).show()
            onBack()
        }
    } else {
        content(result.getOrThrow())
    }
}
