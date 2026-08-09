package me.rerere.fawntavern.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Earth
import com.composables.icons.lucide.Lucide
import java.util.Locale
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.chat.ChatMessage
import me.rerere.fawntavern.data.chat.MsgSearch
import me.rerere.fawntavern.data.chat.SearchCitation
import me.rerere.fawntavern.data.api.ReasoningLevel
import me.rerere.fawntavern.ui.components.reasoningIcon
import me.rerere.fawntavern.ui.components.noRippleClickable
import org.json.JSONArray
import org.json.JSONObject

/*
 * 深度思考 / 联网搜索的时间线卡片（对齐 Kelivo 的 Chain of Thought 设计）：
 * 圆角 16 的浅色容器内，思考与搜索作为时间线步骤纵向排列，左侧 24dp 图标列以 1dp 竖线相连。
 * 思考按各搜索"发起时刻"（MsgSearch.reasoningChars/reasoningMs）切成分段，与搜索步骤交错，
 * 还原"思考→搜索→再思考"的真实时序；思考步骤标题带该段耗时，流式期间最后一段未展开时
 * 显示限高预览（上下渐隐、自动滚底）；搜索步骤点击弹出参数/结果详情面板（可切 JSON 视图）。
 * 引用以"N个引用"胶囊卡挂在正文之后，点击弹出来源列表。
 */

private val TimelineIconSize = 18.dp
private val TimelineIconColumnWidth = 24.dp
private val TimelineLineGap = 3.dp
private val StepPaddingV = 8.dp

/** 展开/收起的缓动与 Kelivo 一致（Cubic(0.2, 0.8, 0.2, 1)、300ms） */
private val CotEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/** 时间线步骤：key 用于 Compose 定位（流式期间步骤数增长，状态不得串位） */
private sealed interface CotStep { val key: String }
private data class ThinkStep(
    val text: String,
    val ms: Long,
    val live: Boolean,   // 是否为最后一段（流式期间显示实时耗时与预览）
    val idx: Int,
) : CotStep { override val key get() = "t$idx" }
private data class SearchStep(
    val search: MsgSearch,
    val idx: Int,        // 在 msg.searches 里的下标（详情面板按此定位）
) : CotStep { override val key get() = "s$idx" }

/** 思考按各搜索发起时刻切段，与搜索步骤交错成时序列表。段文本 trim 掉首尾空行——
 *  模型思考常以连续换行收尾，不裁掉会在步骤之间撑出大段空白 */
private fun buildSteps(reasoning: String, totalMs: Long, searches: List<MsgSearch>): List<CotStep> = buildList {
    var thinkIdx = 0
    var prevChar = 0
    var prevMs = 0L
    searches.forEachIndexed { i, s ->
        val boundary = s.reasoningChars.coerceIn(prevChar, reasoning.length)
        val seg = reasoning.substring(prevChar, boundary).trim()
        if (seg.isNotEmpty()) {
            add(ThinkStep(seg, (s.reasoningMs - prevMs).coerceAtLeast(0), live = false, idx = thinkIdx++))
        }
        add(SearchStep(s, i))
        prevChar = boundary
        prevMs = maxOf(prevMs, s.reasoningMs)
    }
    val tail = reasoning.substring(prevChar).trim()
    if (tail.isNotEmpty()) {
        add(ThinkStep(tail, (totalMs - prevMs).coerceAtLeast(0), live = true, idx = thinkIdx))
    }
}

@Composable
internal fun ThoughtTimelineCard(
    msg: ChatMessage,
    isStreaming: Boolean,
    scale: Float = 1.0f,
) {
    if (msg.searches.isEmpty() && msg.reasoning.isBlank()) return
    val steps = buildSteps(msg.reasoning, msg.reasoningMs, msg.searches)
    if (steps.isEmpty()) return

    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // 点击搜索步骤弹详情面板：记录被点搜索在 msg.searches 里的下标（-1 = 关闭）
    var detailIdx by rememberSaveable { mutableIntStateOf(-1) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (dark) 0.25f else 0.30f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .animateContentSize(tween(300, easing = CotEasing)),
    ) {
        steps.forEachIndexed { i, step ->
            key(step.key) {
                when (step) {
                    is ThinkStep -> ReasoningTimelineStep(
                        text = step.text,
                        durationMs = step.ms,
                        loading = step.live && isStreaming && msg.content.isBlank(),
                        isFirst = i == 0, isLast = i == steps.size - 1,
                        scale = scale,
                    )
                    is SearchStep -> SearchTimelineStep(
                        search = step.search,
                        isFirst = i == 0, isLast = i == steps.size - 1,
                        scale = scale,
                        onClick = { detailIdx = step.idx },
                    )
                }
            }
        }
    }
    msg.searches.getOrNull(detailIdx)?.let { search ->
        SearchDetailSheet(search = search, scale = scale) { detailIdx = -1 }
    }
}

/** 步骤外壳：左侧图标列（上下连接线）+ 右侧内容列 */
@Composable
private fun TimelineStepShell(
    isFirst: Boolean,
    isLast: Boolean,
    scale: Float,
    icon: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(
            Modifier.width(TimelineIconColumnWidth * scale).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 顶部线段 8dp + 3dp 间隙：让 18dp 图标的中心恰好落在 13sp 标题行的视觉中心
            //（CJK 字形重心偏低，按内边距 8dp 对齐图标顶会显得图标偏高约 3dp）
            Box(
                Modifier.width(1.dp).height(StepPaddingV)
                    .background(if (isFirst) Color.Transparent else lineColor)
            )
            Spacer(Modifier.height(TimelineLineGap))
            Box(Modifier.size(TimelineIconSize * scale), contentAlignment = Alignment.Center) { icon() }
            Spacer(Modifier.height(TimelineLineGap))
            Box(
                Modifier.width(1.dp).weight(1f)
                    .background(if (isLast) Color.Transparent else lineColor)
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(vertical = StepPaddingV)) { content() }
    }
}

/** 流式进行中的标题微光：透明度呼吸脉动 */
@Composable
private fun Modifier.shimmerWhile(active: Boolean): Modifier {
    if (!active) return this
    val transition = rememberInfiniteTransition(label = "cotShimmer")
    val a by transition.animateFloat(
        initialValue = 0.45f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
        label = "cotShimmerAlpha",
    )
    return alpha(a)
}

/** 联网搜索步骤：搜索中标题微光；点击弹出参数/结果详情面板（ChevronRight 靠最右缘，与思考步骤的展开图标对齐） */
@Composable
private fun SearchTimelineStep(
    search: MsgSearch,
    isFirst: Boolean,
    isLast: Boolean,
    scale: Float,
    onClick: () -> Unit,
) {
    val labelStyle = MaterialTheme.typography.labelLarge
        .copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold).scaledBy(scale)
    TimelineStepShell(
        isFirst = isFirst, isLast = isLast, scale = scale,
        icon = {
            Icon(Lucide.Earth, null, Modifier.size(TimelineIconSize * scale),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().noRippleClickable { onClick() },
        ) {
            Text(
                stringResource(R.string.cot_web_search_fmt, search.query),
                style = labelStyle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).shimmerWhile(search.searching),
            )
            Icon(
                Lucide.ChevronRight, null,
                Modifier.size(16.dp * scale),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 深度思考步骤（一段）：标题 + 该段耗时 + 展开指示；流式最后一段未展开时限高预览（渐隐、自动滚底） */
@Composable
private fun ReasoningTimelineStep(
    text: String,
    durationMs: Long,
    loading: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    scale: Float,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val labelStyle = MaterialTheme.typography.labelLarge
        .copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold).scaledBy(scale)
    val bodyStyle = MaterialTheme.typography.bodySmall.scaledBy(scale)

    TimelineStepShell(
        isFirst = isFirst, isLast = isLast, scale = scale,
        icon = {
            Icon(reasoningIcon(ReasoningLevel.MEDIUM), null, Modifier.size(TimelineIconSize * scale),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().noRippleClickable { expanded = !expanded },
        ) {
            Text(
                stringResource(R.string.cot_deep_thinking),
                style = labelStyle,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.shimmerWhile(loading),
            )
            if (durationMs > 0) {
                Text(
                    String.format(Locale.US, "(%.1fs)", durationMs / 1000.0f),
                    style = labelStyle.copy(fontWeight = FontWeight.Normal),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            Icon(
                if (expanded) Lucide.ChevronUp else Lucide.ChevronDown, null,
                Modifier.size(16.dp * scale),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            expanded -> Text(
                text, style = bodyStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            loading -> {
                // 流式预览：限高 100dp，内容随生成自动滚到底，上下边缘渐隐
                val scroll = rememberScrollState()
                LaunchedEffect(text.length) { scroll.scrollTo(scroll.maxValue) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .heightIn(max = 100.dp)
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                Brush.verticalGradient(
                                    0f to Color.Transparent, 1f to Color.Black,
                                    endY = 12.dp.toPx(),
                                ),
                                blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                            )
                            // 底部渐隐带只留 14dp：太宽会把最新的思考文字洗成一大段"空白"
                            drawRect(
                                Brush.verticalGradient(
                                    0f to Color.Black, 1f to Color.Transparent,
                                    startY = size.height - 14.dp.toPx(), endY = size.height,
                                ),
                                blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                            )
                        }
                        .verticalScroll(scroll),
                ) {
                    Text(text, style = bodyStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ── 引用展示 ──

private fun citationHost(url: String): String =
    runCatching { java.net.URI(url).host ?: "" }.getOrDefault("")
        .removePrefix("www.").ifBlank { url }

/** 单个来源的 favicon：底层首字母圆片打底，favicon 加载成功后盖在上面 */
@Composable
private fun CitationFavicon(url: String, size: androidx.compose.ui.unit.Dp) {
    val host = citationHost(url)
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            host.take(1).uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (host.contains('.')) {
            AsyncImage(
                model = "https://favicone.com/$host?s=64",
                contentDescription = null,
                modifier = Modifier.size(size).clip(CircleShape),
            )
        }
    }
}

/** 最多 3 个来源 favicon 叠放（步长 11dp，对齐 Kelivo） */
@Composable
private fun FaviconStack(items: List<SearchCitation>, scale: Float) {
    val shown = items.take(3)
    val icon = 16.dp * scale
    val step = 11.dp * scale
    Box(Modifier.width(icon + step * (shown.size - 1)).height(icon)) {
        shown.forEachIndexed { i, item ->
            Box(Modifier.offset(x = step * i)) { CitationFavicon(item.url, icon) }
        }
    }
}

/** 消息正文下方的"N个引用"胶囊卡 */
@Composable
internal fun CitationsPill(
    items: List<SearchCitation>,
    scale: Float = 1.0f,
    onClick: () -> Unit,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val borderColor = if (dark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.10f)
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .border(0.8.dp, borderColor, RoundedCornerShape(20.dp))
            .noRippleClickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FaviconStack(items, scale)
        Text(
            stringResource(R.string.cot_citations_fmt, items.size),
            style = MaterialTheme.typography.labelMedium
                .copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold).scaledBy(scale),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 单条来源卡片：favicon + 站点 + 序号徽标 + 标题 + 摘要，点击跳外部浏览器 */
@Composable
private fun CitationCard(index: Int, item: SearchCitation, scale: Float) {
    val uriHandler = LocalUriHandler.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .noRippleClickable {
                runCatching { uriHandler.openUri(item.url) }
            }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CitationFavicon(item.url, 18.dp * scale)
            Text(
                citationHost(item.url),
                style = MaterialTheme.typography.labelSmall.scaledBy(scale),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            // 序号徽标：primary 20% 圆底 + 序号
            Box(
                Modifier.size(20.dp * scale).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$index",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp).scaledBy(scale),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Text(
            item.title.ifBlank { item.url },
            style = MaterialTheme.typography.titleSmall
                .copy(fontWeight = FontWeight.SemiBold).scaledBy(scale),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        if (item.text.isNotBlank()) {
            Text(
                item.text,
                style = MaterialTheme.typography.bodySmall.scaledBy(scale),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 3,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

/** 引用来源列表底部弹窗（"N个引用"胶囊卡点开） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CitationsSheet(
    items: List<SearchCitation>,
    scale: Float = 1.0f,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.cot_search_results),
            style = MaterialTheme.typography.titleMedium.scaledBy(scale),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 24.dp, top = 4.dp),
        ) {
            items(items.size) { i ->
                CitationCard(index = i + 1, item = items[i], scale = scale)
            }
        }
    }
}

/** 键值行（搜索详情面板的参数区） */
@Composable
private fun ParamRow(label: String, value: String, scale: Float) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall.scaledBy(scale),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp * scale),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium).scaledBy(scale),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** JSON 代码块（详情面板的原始视图，可长按选择复制） */
@Composable
private fun JsonBlock(text: String, scale: Float) {
    SelectionContainer {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall
                .copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp).scaledBy(scale),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(12.dp),
        )
    }
}

/** 详情/JSON 视图切换开关：胶囊分段控件，选中段白底浮起（详情在左、JSON 在右） */
@Composable
private fun DetailViewToggle(json: Boolean, scale: Float, onChange: (Boolean) -> Unit) {
    @Composable
    fun Segment(label: String, selected: Boolean, onClick: () -> Unit) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp).scaledBy(scale),
            color = if (selected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
                .noRippleClickable { onClick() }
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(2.dp),
    ) {
        Segment(stringResource(R.string.cot_search_view_detail), !json) { onChange(false) }
        Segment(stringResource(R.string.cot_search_view_json), json) { onChange(true) }
    }
}

/**
 * 搜索步骤详情底部弹窗：参数（关键词/搜索服务/结果数）+ 结果列表；
 * 标题下一行右侧的分段开关切换 JSON 原始视图（工具调用的 arguments 与回传给模型的结果，对齐 Kelivo）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchDetailSheet(
    search: MsgSearch,
    scale: Float = 1.0f,
    onDismiss: () -> Unit,
) {
    var jsonView by rememberSaveable { mutableStateOf(false) }
    val paramsJson = remember(search.query) {
        runCatching { JSONObject().put("query", search.query).toString(2) }.getOrDefault(search.query)
    }
    val resultJson = remember(search.items) {
        runCatching {
            JSONObject().put("items", JSONArray().apply {
                search.items.forEachIndexed { i, item ->
                    put(JSONObject()
                        .put("index", i + 1)
                        .put("title", item.title)
                        .put("url", item.url)
                        .put("content", item.text))
                }
            }).toString(2)
        }.getOrDefault("")
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Icon(Lucide.Earth, null, Modifier.size(18.dp * scale),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        stringResource(R.string.cot_web_search_fmt, search.query),
                        style = MaterialTheme.typography.titleMedium.scaledBy(scale),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    DetailViewToggle(json = jsonView, scale = scale) { jsonView = it }
                }
            }
            item {
                Text(
                    stringResource(R.string.cot_search_detail_params),
                    style = MaterialTheme.typography.labelLarge.scaledBy(scale),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                if (jsonView) {
                    JsonBlock(paramsJson, scale)
                } else {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ParamRow(stringResource(R.string.cot_search_detail_query), search.query, scale)
                        if (search.provider.isNotBlank()) {
                            ParamRow(stringResource(R.string.cot_search_detail_provider), search.provider, scale)
                        }
                        ParamRow(stringResource(R.string.cot_search_detail_count), "${search.items.size}", scale)
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.cot_search_detail_results),
                    style = MaterialTheme.typography.labelLarge.scaledBy(scale),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            when {
                search.items.isEmpty() -> item {
                    Text(
                        stringResource(
                            if (search.searching) R.string.cot_searching else R.string.cot_search_no_results),
                        style = MaterialTheme.typography.bodySmall.scaledBy(scale),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                jsonView -> item { JsonBlock(resultJson, scale) }
                else -> items(search.items.size) { i ->
                    CitationCard(index = i + 1, item = search.items[i], scale = scale)
                }
            }
        }
    }
}
