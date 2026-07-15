package me.rerere.stapp.ui.api

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

enum class TagType {
    DEFAULT,
    SUCCESS,
    ERROR,
    WARNING,
    INFO,
}

@Composable
fun Tag(
    modifier: Modifier = Modifier,
    type: TagType = TagType.DEFAULT,
    content: @Composable RowScope.() -> Unit,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val (bg, fg) = when (type) {
        TagType.SUCCESS -> if (dark) Color(0xFF1B3A26) to Color(0xFF86EFAC) else Color(0xFFDCFCE7) to Color(0xFF166534)
        TagType.WARNING -> if (dark) Color(0xFF44290F) to Color(0xFFFDBA74) else Color(0xFFFFEDD5) to Color(0xFF9A3412)
        TagType.ERROR -> if (dark) Color(0xFF451717) to Color(0xFFFCA5A5) else Color(0xFFFEE2E2) to Color(0xFF991B1B)
        TagType.INFO -> if (dark) Color(0xFF172A54) to Color(0xFF93C5FD) else Color(0xFFDBEAFE) to Color(0xFF1E40AF)
        TagType.DEFAULT -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    }
    ProvideTextStyle(MaterialTheme.typography.labelSmall.copy(color = fg)) {
        Row(
            modifier = modifier
                .clip(RoundedCornerShape(50))
                .background(bg)
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content()
        }
    }
}
