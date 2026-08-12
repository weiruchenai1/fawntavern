package me.rerere.fawntavern.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 多行文本输入框：所有会被内容撑高、需要内部滚动的编辑框都走这里。
 *
 * 限高必须用 [maxLines] 走 lineLimits，**不能**在 modifier 上写 heightIn(max=…)：
 * 旧版 OutlinedTextField(value=…) 的「把光标滚进视口」是布局之后的副作用，用的是重测量前
 * 算出的 cursorRect —— 键盘弹出那一帧窗口刚好在变高，落点就偏了一段（点中间的字，光标跑到别行）。
 * BTF2 把这套逻辑放进测量阶段，每帧用当帧的 textLayout 重算，没有陈旧值可用。
 * 而 BTF2 下外部 heightIn(max=…) 只会裁剪不会滚动，两者必须成对替换。
 */
@Composable
fun AppTextArea(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    minLines: Int = 3,
    maxLines: Int = 12,
    enabled: Boolean = true,
    readOnly: Boolean = false,
) {
    OutlinedTextField(
        state = state,
        modifier = modifier.fillMaxWidth(),
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        lineLimits = TextFieldLineLimits.MultiLine(
            minHeightInLines = minLines,
            maxHeightInLines = maxLines,
        ),
        enabled = enabled,
        readOnly = readOnly,
    )
}
