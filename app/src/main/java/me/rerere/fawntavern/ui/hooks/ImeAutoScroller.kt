package me.rerere.fawntavern.ui.hooks

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
 * 是否跟随由键盘高度变化前的 [shouldFollow] 决定（如"贴底才跟随"），弹出/收起期间
 * 沿用该决定，用户滚动时终止本轮跟随；阅读历史时键盘收起也不抢回位置。
 *
 * [onFollow] 应走调用方滚动状态机的钉底入口，以共用同一套手势让位判断；它必须是
 * requestScrollToItem 一类"下次测量生效"的钉底，不能按高度差 scrollBy——后者与视口重测量
 * 存在竞态：贴底时可滚余量尚未增长，scrollBy 被 clamp 掉一部分且无后续校正，最终上移量
 * 小于键盘高度。
 */
@Composable
fun ImeLazyListAutoScroller(
    lazyListState: LazyListState,
    shouldFollow: () -> Boolean,
    onFollow: () -> Unit,
    imeInsets: WindowInsets = WindowInsets.ime,
) {
    val localDensity by rememberUpdatedState(LocalDensity.current)
    val ime by rememberUpdatedState(imeInsets)
    val follow by rememberUpdatedState(shouldFollow)
    val doFollow by rememberUpdatedState(onFollow)
    LaunchedEffect(lazyListState) {
        var previous: ImeScrollSnapshot? = null
        var following = false
        snapshotFlow {
            ImeScrollSnapshot(
                height = ime.getBottom(localDensity),
                shouldFollow = follow(),
                scrolling = lazyListState.isScrollInProgress,
            )
        }.collect { current ->
            val before = previous
            if ((before?.height ?: 0) == 0 && current.height > 0) {
                // IME 事件可能晚于视口缩小的测量，必须使用变化前的贴底状态。
                following = before?.shouldFollow ?: current.shouldFollow
            }
            // 用户开始滚动后，本轮键盘动画不再抢回位置。
            if (current.scrolling) following = false
            if (following && current.height != (before?.height ?: 0)) {
                doFollow()
            }
            previous = current
        }
    }
}

private data class ImeScrollSnapshot(
    val height: Int,
    val shouldFollow: Boolean,
    val scrolling: Boolean,
)
