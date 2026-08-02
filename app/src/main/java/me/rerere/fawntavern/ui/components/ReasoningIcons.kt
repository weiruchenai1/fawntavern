package me.rerere.fawntavern.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import me.rerere.fawntavern.data.api.ReasoningLevel

/**
 * 思考预算图标组：同一只灯泡，档位越高向外发散的光线越多、越长。
 *
 * 灯罩与灯头取自 HugeIcons 的 Idea01（rikkahub 同源）；光线本身是从 (12,12) 出发、
 * 半径 9→10 的直线段，故按角度参数化生成——"中"档生成出来与 Idea01 原图逐点一致。
 * 描边宽度取 2（原图为 1.5），与输入栏里其它 Lucide 图标视觉重量对齐。
 */

private const val CX = 12f          // 灯泡中心，也是光线的发散中心
private const val CY = 12f
private const val RAY_IN = 9f       // 光线内端半径
private const val STROKE = 2f

// 光线角度（0 = 正右、90 = 正上、180 = 正左），按档位逐级加密：
// 低档只在顶部三道，中档补上水平两道，高档铺满上半圈
private val RAYS_LOW = listOf(45f, 90f, 135f)
private val RAYS_MEDIUM = RAYS_LOW + listOf(0f, 180f)
private val RAYS_HIGH = RAYS_MEDIUM + listOf(22.5f, 67.5f, 112.5f, 157.5f)

// 裸灯泡（无光线）本身只占 x 5.5..18.5 / y 5.5..22，放大 1.2 倍并居中才撑满视口，
// 否则与带光线的档位摆在一起会小一号
private const val BARE_SCALE = 1.2f
private const val BULB_CY = 13.77f  // 灯泡图形自身的纵向中心

val ReasoningOffIcon: ImageVector by lazy { bulbIcon("ReasoningOff", emptyList(), scale = BARE_SCALE) }
val ReasoningLowIcon: ImageVector by lazy { bulbIcon("ReasoningLow", RAYS_LOW) }
val ReasoningMediumIcon: ImageVector by lazy { bulbIcon("ReasoningMedium", RAYS_MEDIUM) }
val ReasoningHighIcon: ImageVector by lazy { bulbIcon("ReasoningHigh", RAYS_HIGH) }
// 角度已铺满上半圈，极高档改为把每道光线拉长一倍
val ReasoningXHighIcon: ImageVector by lazy { bulbIcon("ReasoningXHigh", RAYS_HIGH, rayOut = 11f) }

/**
 * 按档位取图标。关闭 = 一只不发光的灯泡；自动 = 与"中"同一个图标
 * （未指定强度、交给模型默认，二者在输入栏里外观相同，具体档位以面板内的勾选为准）。
 */
fun reasoningIcon(level: ReasoningLevel): ImageVector = when (level) {
    ReasoningLevel.OFF -> ReasoningOffIcon
    ReasoningLevel.AUTO, ReasoningLevel.MEDIUM -> ReasoningMediumIcon
    ReasoningLevel.LOW -> ReasoningLowIcon
    ReasoningLevel.HIGH -> ReasoningHighIcon
    ReasoningLevel.XHIGH -> ReasoningXHighIcon
}

private fun bulbIcon(
    name: String,
    rays: List<Float>,
    rayOut: Float = 10f,
    scale: Float = 1f,
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f,
).apply {
    fun stroke(width: Float = STROKE, block: PathBuilder.() -> Unit) = path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block,
    )
    // 组内描边会被 scale 一并放大，故预先调细，渲染出来仍是 STROKE
    val bulbStroke = STROKE / scale
    if (scale != 1f) {
        // 以灯泡自身中心为基准放大，再上移到视口中心
        addGroup(
            pivotX = CX, pivotY = BULB_CY,
            scaleX = scale, scaleY = scale,
            translationY = CY - BULB_CY,
        )
    }
    // 灯罩
    stroke(bulbStroke) {
        moveTo(6.08938f, 14.9992f)
        curveTo(5.71097f, 14.1486f, 5.5f, 13.2023f, 5.5f, 12.2051f)
        curveTo(5.5f, 8.50154f, 8.41015f, 5.49921f, 12f, 5.49921f)
        curveTo(15.5899f, 5.49921f, 18.5f, 8.50154f, 18.5f, 12.2051f)
        curveTo(18.5f, 13.2023f, 18.289f, 14.1486f, 17.9106f, 14.9992f)
    }
    // 灯头
    stroke(bulbStroke) {
        moveTo(14.517f, 19.3056f)
        curveTo(15.5274f, 18.9788f, 15.9326f, 18.054f, 16.0466f, 17.1238f)
        curveTo(16.0806f, 16.8459f, 15.852f, 16.6154f, 15.572f, 16.6154f)
        lineTo(8.47685f, 16.6156f)
        curveTo(8.18725f, 16.6156f, 7.95467f, 16.8614f, 7.98925f, 17.1489f)
        curveTo(8.1009f, 18.0773f, 8.3827f, 18.7555f, 9.45345f, 19.3056f)
        moveTo(14.517f, 19.3056f)
        curveTo(14.517f, 19.3056f, 9.62971f, 19.3056f, 9.45345f, 19.3056f)
        moveTo(14.517f, 19.3056f)
        curveTo(14.3955f, 21.2506f, 13.8338f, 22.0209f, 12.0068f, 21.9993f)
        curveTo(10.0526f, 22.0354f, 9.60303f, 21.0833f, 9.45345f, 19.3056f)
    }
    if (scale != 1f) clearGroup()
    rays.forEach { deg ->
        stroke {
            moveTo(CX + RAY_IN * cos(deg.rad), CY - RAY_IN * sin(deg.rad))
            lineTo(CX + rayOut * cos(deg.rad), CY - rayOut * sin(deg.rad))
        }
    }
}.build()

/** 角度转弧度。视口 y 轴向下，故由角度算纵坐标时取负 */
private val Float.rad: Float get() = (this * PI / 180f).toFloat()
