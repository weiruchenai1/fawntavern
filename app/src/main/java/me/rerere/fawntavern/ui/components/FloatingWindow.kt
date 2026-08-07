package me.rerere.fawntavern.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import com.petterp.floatingx.FloatingX
import com.petterp.floatingx.assist.FxGravity
import com.petterp.floatingx.listener.control.IFxAppControl
import me.rerere.fawntavern.data.settings.ThemeMode
import me.rerere.fawntavern.ui.theme.FawnTavernTheme

/**
 * 系统级悬浮窗：用 FloatingX 把 [content] 挂到 App 窗口之上，
 * 悬浮在屏幕上、可拖拽移动；[visibility] 控制显示/隐藏。content 内部读 Compose 状态即随其刷新。
 */
@Composable
fun FloatingWindow(
    tag: String,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    visibility: Boolean = true,
    initialOffsetX: Float = 0f,
    initialOffsetY: Float = 0f,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var window: IFxAppControl? by remember { mutableStateOf(null) }

    LaunchedEffect(visibility) {
        if (visibility) window?.show() else window?.hide()
    }

    DisposableEffect(context) {
        window = FloatingX.install {
            setTag(tag)
            setContext(context)
            // 默认贴左上角；[initialOffset] 为相对左上角的初始偏移（px）
            setGravity(FxGravity.LEFT_OR_TOP)
            setOffsetXY(initialOffsetX, initialOffsetY)
            setEnableAnimation(true)
            setLayoutView(ComposeView(context).apply {
                setContent {
                    FawnTavernTheme(themeMode = themeMode) {
                        content()
                    }
                }
            })
        }
        if (visibility) window?.show() else window?.hide()
        onDispose {
            window?.cancel()
        }
    }
}
