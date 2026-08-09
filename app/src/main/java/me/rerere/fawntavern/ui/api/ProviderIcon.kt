package me.rerere.fawntavern.ui.api

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.svg.css

/**
 * 提供商品牌图标：从 assets/icons/{slug}.svg 用 Coil 渲染（SvgDecoder 在 MainActivity 注册）。
 * 单色图标（fill="currentColor"）用 CSS 染成主题前景色，深浅色模式都清晰；
 * 彩色图标路径自带 fill，不受 CSS 影响。无匹配图标时回退首字头像。
 */
@Composable
fun ProviderIcon(name: String, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val slug = iconSlug(name)
    if (slug.isEmpty()) {
        LetterAvatar(name, size, modifier)
        return
    }
    val shape = RoundedCornerShape((size / 5).value.dp)
    val contentColor = MaterialTheme.colorScheme.onSurface
    val context = LocalContext.current
    val model = remember(slug, contentColor, context) {
        ImageRequest.Builder(context)
            .data("file:///android_asset/icons/$slug.svg")
            .css("svg { fill: ${contentColor.toCssHex()}; color: ${contentColor.toCssHex()}; }")
            .build()
    }
    AsyncImage(
        model = model,
        contentDescription = name,
        modifier = modifier.size(size).clip(shape),
    )
}

@Composable
private fun LetterAvatar(name: String, size: Dp, modifier: Modifier) {
    val shape = RoundedCornerShape((size / 5).value.dp)
    Box(
        modifier = modifier.size(size).clip(shape).background(Color(0xFF666666)),
        contentAlignment = Alignment.Center,
    ) {
        // lineHeight 须等于 fontSize：否则继承全局 typography 的大行高，glyph 在行内偏上不居中
        Text(name.take(1).uppercase(), color = Color.White,
            fontSize = (size.value * 0.45f).sp,
            lineHeight = (size.value * 0.45f).sp,
            textAlign = TextAlign.Center)
    }
}

/** Color 转 CSS hex（含 alpha），供 Coil .css() 染色单色 SVG */
private fun Color.toCssHex(): String {
    val alpha = (alpha * 255).toInt()
    val red = (red * 255).toInt()
    val green = (green * 255).toInt()
    val blue = (blue * 255).toInt()
    return "#${String.format("%02X%02X%02X%02X", red, green, blue, alpha)}"
}

/** 将提供商名称映射为图标资源名（slug，对应 assets/icons/{slug}.svg）。 */
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
        n.contains("doubao") || n.contains("豆包") || n.contains("ark") -> "doubao_color"
        n.contains("glm") || n.contains("智谱") -> "zhipu_color"
        n.contains("阿里") || n.contains("百炼") || n.contains("alibabacloud") -> "alibabacloud_color"
        n.contains("火山") || n.contains("volcengine") || n.contains("volces") -> "volcengine_color"
        n.contains("kling") || n.contains("可灵") -> "kling_color"
        n.contains("moonshot") || n.contains("月之暗面") -> "moonshot"
        n.contains("kimi") -> "kimi_color"
        n.contains("01.ai") || n.contains("01ai") || n.contains("yi-") || n.contains("lingyi") || n.contains("零一") -> "yi_color"
        n.contains("bing") -> "bing_color"
        n.contains("tavily") -> "tavily_color"
        n.contains("exa") -> "exa_color"
        n.contains("zhipu") || n.contains("智谱") -> "zhipu_color"
        n.contains("searxng") -> "searxng_color"
        n.contains("brave") -> "brave_color"
        n.contains("bocha") -> "bocha_color"
        n.contains("perplexity") -> "perplexity_color"
        n.contains("ollama") -> "ollama_color"
        n.contains("jina") -> "jina_color"
        n.contains("duckduckgo") -> "duckduckgo_color"
        n.contains("linkup") -> "linkup_color"
        n.contains("metaso") -> "metaso_color"
        n.contains("serper") -> "serper_color"
        n.contains("querit") -> "querit_color"
        n.contains("minimax") || n.contains("mini max") -> "minimax_color"
        n.contains("mimo") -> "xiaomimimo_color"
        else -> ""
    }
}
