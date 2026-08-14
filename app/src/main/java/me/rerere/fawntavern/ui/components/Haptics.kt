package me.rerere.fawntavern.ui.components

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import me.rerere.fawntavern.core.diagnostics.SafeLog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback

private const val HAPTICS_TAG = "Haptics"

/** 一次短震动（开关/侧边栏触觉用）。走系统 Vibrator，与 LocalHapticFeedback 的 LongPress 无关。 */
fun vibrate(context: Context, durationMs: Long = 25) {
    val vibrator = context.getSystemService(Vibrator::class.java) ?: return
    if (!vibrator.hasVibrator()) return
    try {
        vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
    } catch (error: Exception) {
        SafeLog.warn(HAPTICS_TAG, "haptic_feedback_failed", error)
    }
}

/**
 * 全局长按触觉闸门：把 [LocalHapticFeedback] 换成按偏好过滤的实现。
 *
 * `combinedClickable`（[appClickable] 的长按）、拖拽排序的长按等都走
 * `LocalHapticFeedback.current.performHapticFeedback(LongPress)` —— 在这里统一按
 * "长按触觉反馈"偏好过滤，关闭时静默。偏好在调用时读取，设置页改完即生效。
 *
 * 开关/侧边栏触觉走 [vibrate]（Vibrator），不经过本闸门，由各自的偏好开关在调用点控制。
 */
@Composable
fun HapticGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val base = LocalHapticFeedback.current
    val settings = remember(context) {
        HapticSettingsController(AndroidHapticSettingsDataSource(context))
    }
    val gate = remember(settings, base) {
        object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                if (hapticFeedbackType == HapticFeedbackType.LongPress &&
                    !settings.longPressEnabled()
                ) return
                base.performHapticFeedback(hapticFeedbackType)
            }
        }
    }
    CompositionLocalProvider(LocalHapticFeedback provides gate) { content() }
}
