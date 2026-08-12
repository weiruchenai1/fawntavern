package me.rerere.fawntavern.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Lucide
import me.rerere.fawntavern.R

/**
 * 全屏底部面板：展示/编辑一段文本（消息全文只读 / 输入框全文可编辑共用），
 * 标题在左上、复制全部按钮固定在右上角，正文独立滚动。
 * [editState] 非空即可编辑，它就是输入框那个 TextFieldState，两处共用同一实例，改动天然同步。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TextCopySheet(
    title: String,
    text: String,
    onCopyAll: () -> Unit,
    onDismiss: () -> Unit,
    editState: TextFieldState? = null,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.8f).padding(horizontal = 16.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 标题不占权重，Spacer 吃满中间空隙；按钮（含默认内边距）整体右缘贴内容区最右
                Text(title, style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onCopyAll) {
                    Icon(Lucide.Copy, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.copy_all))
                }
            }
            if (editState != null) {
                // 可编辑模式（输入框展开面板）：与输入框同一 TextFieldState
                // 不套外层 verticalScroll —— BTF2 会在 weight 给出的高度约束内自己滚动，
                // 外层滚动容器反而让它以无限高度测量，光标进视口只能靠陈旧的 cursorRect
                BasicTextField(
                    state = editState,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    lineLimits = TextFieldLineLimits.MultiLine(),
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 24.dp),
                )
            } else {
                // 只读模式（消息全文）：可长按选择，weight 落在 Column 直接子节点上
                SelectionContainer(Modifier.fillMaxWidth().weight(1f)) {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 24.dp),
                    )
                }
            }
        }
    }
}
