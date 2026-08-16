package me.rerere.fawntavern.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.Lucide
import me.rerere.fawntavern.R

/**
 * 通用页头：返回按钮 + 居中标题 + 可选右侧操作。左右各约 32dp，标题在剩余空间居中即整体居中。
 * [actions] 缺省时右侧留 32dp 占位与返回键对称；传入时约定为单个 32dp 的 [AppIconButton] 以保持居中。
 */
@Composable
fun AppTopBar(title: String, onBack: () -> Unit, actions: (@Composable () -> Unit)? = null) {
    Box(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding().padding(horizontal = Space16, vertical = Space8),
    ) {
        AppIconButton(
            icon = Lucide.ChevronLeft,
            contentDescription = stringResource(R.string.back),
            onClick = onBack,
            container = MaterialTheme.colorScheme.surfaceContainerHighest,
            size = 32.dp,
            iconSize = 24.dp,
            modifier = Modifier.align(Alignment.CenterStart),
        )
        Text(title, style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 48.dp),
            textAlign = TextAlign.Center,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (actions != null) {
            Box(Modifier.align(Alignment.CenterEnd)) { actions() }
        } else {
            Spacer(Modifier.size(32.dp).align(Alignment.CenterEnd))
        }
    }
}
