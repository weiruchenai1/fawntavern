package me.rerere.fawntavern.ui.api

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Link
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Type
import com.composables.icons.lucide.Wrench
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.api.BuiltInTool
import me.rerere.fawntavern.data.api.Modality
import me.rerere.fawntavern.data.api.ModelAbility
import me.rerere.fawntavern.data.api.ModelInfo
import me.rerere.fawntavern.data.api.ReasoningLevel
import me.rerere.fawntavern.ui.components.reasoningIcon

/**
 * 模型能力标签组：模态（输入→输出）、能力、已开启的内置工具，各占一枚 [Tag]。
 * 会连续发出多个标签，调用方把它放进 FlowRow 之类的容器里。
 */
@Composable
fun ModelCapabilityTags(model: ModelInfo) {
    Tag(type = TagType.SUCCESS) {
        Row(horizontalArrangement = Arrangement.spacedBy(1.dp),
            verticalAlignment = Alignment.CenterVertically) {
            model.inputModalities.forEach { TagIcon(it.icon(), it.label()) }
            TagIcon(Lucide.ChevronRight, null)
            model.outputModalities.forEach { TagIcon(it.icon(), it.label()) }
        }
    }
    if (ModelAbility.TOOL in model.abilities) {
        Tag(type = TagType.WARNING) { TagIcon(Lucide.Wrench, stringResource(R.string.ability_tool)) }
    }
    if (ModelAbility.REASONING in model.abilities) {
        // 与思考预算面板同一只灯泡（默认档），两处指的是同一件事
        Tag(type = TagType.INFO) {
            TagIcon(reasoningIcon(ReasoningLevel.AUTO), stringResource(R.string.ability_reasoning))
        }
    }
    model.tools.forEach { tool ->
        Tag(type = TagType.DEFAULT) { TagIcon(tool.icon(), tool.label()) }
    }
}

/**
 * 标签内的小图标：尺寸对齐文字行高，纯文字标签与带图标标签的胶囊高度才一致；
 * 颜色跟随 [Tag] 提供的文字颜色，Icon 默认的 LocalContentColor 在标签底色上会看不清。
 */
@Composable
private fun TagIcon(icon: ImageVector, contentDescription: String?) {
    val style = LocalTextStyle.current
    val size = with(LocalDensity.current) {
        if (style.lineHeight.type == TextUnitType.Sp) style.lineHeight.toDp() else 16.dp
    }
    Icon(icon, contentDescription, Modifier.size(size), tint = style.color)
}

@Composable
private fun Modality.icon() = when (this) {
    Modality.TEXT -> Lucide.Type
    Modality.IMAGE -> Lucide.Image
}

@Composable
fun Modality.label(): String = stringResource(
    when (this) {
        Modality.TEXT -> R.string.modality_text
        Modality.IMAGE -> R.string.modality_image
    }
)

@Composable
private fun BuiltInTool.icon() = when (this) {
    BuiltInTool.SEARCH -> Lucide.Globe
    BuiltInTool.URL_CONTEXT -> Lucide.Link
}

@Composable
fun BuiltInTool.label(): String = stringResource(
    when (this) {
        BuiltInTool.SEARCH -> R.string.builtin_tool_search
        BuiltInTool.URL_CONTEXT -> R.string.builtin_tool_url_context
    }
)

@Composable
fun ModelAbility.label(): String = stringResource(
    when (this) {
        ModelAbility.TOOL -> R.string.ability_tool
        ModelAbility.REASONING -> R.string.ability_reasoning
    }
)
