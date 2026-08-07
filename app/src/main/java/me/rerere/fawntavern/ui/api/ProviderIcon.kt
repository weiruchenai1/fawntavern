package me.rerere.fawntavern.ui.api

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ProviderIcon(name: String, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val resName = iconSlug(name)
    val shape = RoundedCornerShape((size / 5).value.dp)

    val bitmap = remember(name) {
        try {
            val id = context.resources.getIdentifier(resName, "drawable", context.packageName)
            if (id != 0) {
                BitmapFactory.decodeResource(context.resources, id)?.asImageBitmap()
            } else null
        } catch (_: Exception) { null }
    }

    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = name,
            modifier = modifier.size(size).clip(shape))
    } else {
        // 回退：彩色文字头像
        val color = providerColor(name)
        Box(
            modifier = modifier.size(size).clip(shape).background(color),
            contentAlignment = Alignment.Center,
        ) {
            // lineHeight 须等于 fontSize：否则继承全局 typography 的大行高，glyph 在行内偏上不居中
            Text(name.take(1).uppercase(), color = androidx.compose.ui.graphics.Color.White,
                fontSize = (size.value * 0.45f).sp,
                lineHeight = (size.value * 0.45f).sp,
                textAlign = TextAlign.Center)
        }
    }
}

/** 将提供商名称映射为图标资源名（slug）。 */
private fun iconSlug(name: String): String {
    val n = name.lowercase()
    return when {
        n.contains("openai") || n.contains("gpt") || n.startsWith("o1") || n.startsWith("o3") || n.startsWith("o4") -> "openai_color"
        n.contains("google") && !n.contains("gemini") -> "google_color"
        n.contains("gemini") -> "gemini_color"
        n.contains("claude") || n.contains("anthropic") -> "claude_color"
        n.contains("deepseek") -> "deepseek_color"
        n.contains("groq") -> "groq_color"
        n.contains("openrouter") -> "openrouter_color"
        n.contains("mistral") -> "mistral_color"
        n.contains("silicon") -> "siliconflow_color"
        n.contains("newapi") || n.contains("new api") || n.contains("new-api") -> "newapi_color"
        n.contains("grok") || n.contains("xai") || n.contains("x.ai") -> "grok_color"
        n.contains("qwen") || n.contains("dashscope") || n.contains("通义") -> "qwen_color"
        n.contains("doubao") || n.contains("豆包") || n.contains("volces") || n.contains("ark") -> "doubao_color"
        n.contains("kling") || n.contains("可灵") -> "kling_color"
        n.contains("moonshot") || n.contains("kimi") || n.contains("月之暗面") -> "moonshot_color"
        n.contains("01.ai") || n.contains("01ai") || n.contains("yi-") || n.contains("lingyi") || n.contains("零一") -> "yi_color"
        else -> ""
    }
}

private fun providerColor(name: String): androidx.compose.ui.graphics.Color {
    val n = name.lowercase()
    return when {
        n.contains("openai") || n.contains("gpt") -> androidx.compose.ui.graphics.Color(0xFF000000)
        n.contains("google") || n.contains("gemini") -> androidx.compose.ui.graphics.Color(0xFF4285F4)
        n.contains("claude") || n.contains("anthropic") -> androidx.compose.ui.graphics.Color(0xFFD97757)
        n.contains("deepseek") -> androidx.compose.ui.graphics.Color(0xFF4D6BFE)
        n.contains("groq") -> androidx.compose.ui.graphics.Color(0xFFF97316)
        n.contains("openrouter") -> androidx.compose.ui.graphics.Color(0xFF6366F1)
        n.contains("mistral") -> androidx.compose.ui.graphics.Color(0xFFF59E0B)
        n.contains("silicon") -> androidx.compose.ui.graphics.Color(0xFF7C3AED)
        n.contains("newapi") || n.contains("new api") || n.contains("new-api") -> androidx.compose.ui.graphics.Color(0xFF0EA5E9)
        n.contains("grok") || n.contains("xai") -> androidx.compose.ui.graphics.Color(0xFF000000)
        n.contains("qwen") || n.contains("通义") -> androidx.compose.ui.graphics.Color(0xFF615CED)
        n.contains("doubao") || n.contains("豆包") -> androidx.compose.ui.graphics.Color(0xFF17594A)
        n.contains("kling") || n.contains("可灵") -> androidx.compose.ui.graphics.Color(0xFF10B981)
        n.contains("moonshot") || n.contains("kimi") -> androidx.compose.ui.graphics.Color(0xFF1E1E1E)
        n.contains("01.ai") || n.contains("01ai") || n.contains("yi-") || n.contains("lingyi") || n.contains("零一") -> androidx.compose.ui.graphics.Color(0xFF003425)
        else -> androidx.compose.ui.graphics.Color(0xFF666666)
    }
}
