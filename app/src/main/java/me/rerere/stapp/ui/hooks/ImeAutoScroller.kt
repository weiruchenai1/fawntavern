package me.rerere.stapp.ui.hooks

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity

/**
 * 监听 IME 键盘高度变化，弹出/收起动画期间保持列表钉在底部（补偿视口高度变化）。
 * 是否跟随由键盘开始弹出那一刻的 [shouldFollow] 决定（如"贴底才跟随"），整个
 * 弹出/收起周期沿用该决定：不跟随时视口纹丝不动，键盘收起时也不回滚。
 *
 * 跟随用 requestScrollToItem 越界钉底（下一次测量时生效并 clamp 到真实底部，
 * 调用方列表末尾需有底部锚点行），而不是按高度差 scrollBy——后者与视口重测量
 * 存在竞态：贴底时可滚余量尚未增长，scrollBy 被 clamp 掉一部分且无后续校正，
 * 最终上移量小于键盘高度。
 */
@Composable
fun ImeLazyListAutoScroller(
    lazyListState: LazyListState,
    shouldFollow: () -> Boolean = { true },
) {
    val ime = WindowInsets.ime
    val localDensity = LocalDensity.current
    val follow by rememberUpdatedState(shouldFollow)
    LaunchedEffect(Unit) {
        var imeHeight = 0
        var following = false
        snapshotFlow {
            ime.getBottom(localDensity)
        }.collect { keyboardHeight ->
            if (imeHeight == 0 && keyboardHeight > 0) following = follow()
            // 手势优先，与生成跟随同款保险：用户正在滚动时不抢滚动位置
            if (following && keyboardHeight != imeHeight && !lazyListState.isScrollInProgress) {
                lazyListState.requestScrollToItem(lazyListState.layoutInfo.totalItemsCount + 5)
            }
            imeHeight = keyboardHeight
        }
    }
}
