package me.rerere.fawntavern.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/**
 * 设置类页面的统一外壳：顶部固定导航栏（[AppTopBar]）+ 下方可滚动内容。
 * 页头到内容、内容各段之间的间距统一为 [spacing]（默认 16）；两侧 16dp 边距。
 * 顶部 16dp 放在 scroll 内层（随内容滚动）：静止时是页头到内容的间距，滚动时内容能贴到
 * 页头边缘被正常遮挡；若放在 scroll 外层会让内容在页头下方 16dp 处被裁剪，看着像页头多遮了一块。
 * 仅用于「整页可滚动的分组列表」型页面；LazyColumn / 固定顶栏+列表 / 固定底栏 等结构
 * 保持各自布局，但顶部间距统一沿用 16dp。
 */
@Composable
fun SettingsSubPage(
    title: String,
    onBack: () -> Unit,
    spacing: Dp = Space16,
    scrollable: Boolean = true,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = rememberScrollState()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar(title, onBack) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .padding(horizontal = Space16)
                .then(if (scrollable) Modifier.verticalScroll(scrollState) else Modifier)
                .padding(top = Space16),
            horizontalAlignment = horizontalAlignment,
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            content()
            Spacer(Modifier.height(Space16))
        }
    }
}
