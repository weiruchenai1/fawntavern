package me.rerere.fawntavern.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private const val MIN_FLING_VELOCITY = 365f

/** 整体平移式侧边抽屉状态。单个 [Animatable] 驱动主内容右移 + 抽屉滑入。 */
@Stable
class InteractiveDrawerState(
    internal val progress: Animatable<Float, *>,
    private val scope: CoroutineScope,
    private val animationDuration: Int = 250,
) {
    var isOpen by mutableStateOf(false)
        private set

    fun open() {
        isOpen = true
        scope.launch { progress.animateTo(1f, animationSpec = tween(animationDuration)) }
    }

    fun close() {
        scope.launch { progress.animateTo(0f, animationSpec = tween(animationDuration)) }
        isOpen = false
    }

    fun toggle() { if (isOpen) close() else open() }

    fun snapClose() {
        scope.launch { progress.snapTo(0f) }
        isOpen = false
    }
}

@Composable
fun rememberInteractiveDrawerState(animationDuration: Int = 250): InteractiveDrawerState {
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    return remember { InteractiveDrawerState(progress, scope, animationDuration) }
}

/**
 * 整体平移式侧边抽屉：
 * - 拖拽时手指移动即同步平移
 * - 松手时：惯性速度 ≥ 365 px/s 按方向开/关，否则直接关
 */
@Composable
fun InteractiveDrawer(
    state: InteractiveDrawerState,
    modifier: Modifier = Modifier,
    drawerWidth: Dp = 300.dp,
    maxScrimOpacity: Float = 0.5f,
    drawerContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val widthPx = with(density) { drawerWidth.toPx() }
    val progress = state.progress.value

    BackHandler(enabled = state.isOpen) { state.close() }

    Box(modifier.fillMaxSize()) {
        // 抽屉：从 -drawerWidth 滑到 0。主内容右移后左侧抽屉区不归主内容手势管，必须自己挂拖拽
        Box(
            Modifier
                .fillMaxHeight()
                .width(drawerWidth)
                .offset { IntOffset((-widthPx * (1f - progress)).roundToInt(), 0) }
                .drawerHorizontalDrag(state, widthPx, scope)
        ) { drawerContent() }

        // 主内容右移 + 遮罩 + 手势
        Box(
            Modifier
                .fillMaxSize()
                .offset { IntOffset((widthPx * progress).roundToInt(), 0) }
                .drawerHorizontalDrag(state, widthPx, scope)
        ) {
            content()

            if (progress > 0.01f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .alpha(progress * maxScrimOpacity)
                        .background(Color.Black)
                        .clickable(interactionSource = null, indication = null) { state.close() }
                )
            }
        }
    }
}

/** 横向拖拽平移抽屉进度：随指移动、松手按惯性方向开/关、无惯性直接关。抽屉与主内容各挂一份。 */
private fun Modifier.drawerHorizontalDrag(state: InteractiveDrawerState, widthPx: Float, scope: CoroutineScope): Modifier =
    pointerInput(state, widthPx) {
        // 速度追踪为本次拖拽会话私有，两处各自持有互不串扰
        var lastTimeMs = 0L
        var velocityPxPerSec = 0f
        detectHorizontalDragGestures(
            onDragStart = {
                lastTimeMs = 0L
                velocityPxPerSec = 0f
            },
            onHorizontalDrag = { change, dragAmount ->
                val newVal = (state.progress.value + dragAmount / widthPx).coerceIn(0f, 1f)
                scope.launch { state.progress.snapTo(newVal) }

                val now = change.uptimeMillis
                if (lastTimeMs > 0) {
                    val dt = now - lastTimeMs
                    if (dt > 0) velocityPxPerSec = dragAmount / dt * 1000f
                }
                lastTimeMs = now
            },
            onDragEnd = {
                if (abs(velocityPxPerSec) >= MIN_FLING_VELOCITY) {
                    if (velocityPxPerSec > 0) state.open() else state.close()
                } else {
                    state.close()
                }
            },
            onDragCancel = { state.close() },
        )
    }
