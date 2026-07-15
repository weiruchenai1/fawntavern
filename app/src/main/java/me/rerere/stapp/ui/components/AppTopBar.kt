package me.rerere.stapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.CircleChevronLeft
import com.composables.icons.lucide.Lucide
import me.rerere.stapp.R

/** 通用页头：返回图标 + 居中标题。左右各占 24dp，标题在剩余空间居中即整体居中 */
@Composable
fun AppTopBar(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding().padding(horizontal = Space16, vertical = Space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Lucide.CircleChevronLeft, stringResource(R.string.back), Modifier.size(24.dp).clickable { onBack() },
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.size(24.dp))
    }
}
