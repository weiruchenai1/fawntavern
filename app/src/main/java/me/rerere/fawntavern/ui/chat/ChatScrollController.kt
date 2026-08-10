package me.rerere.fawntavern.ui.chat

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import me.rerere.fawntavern.data.settings.NavButtonsMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 聊天列表滚动状态机 —— 所有滚动位置的单一所有者。
 *
 * 跟随规则两条：**上划即停跟**、**落点触底即跟随**，都在 [settleDrag]（手指抬起且惯性走完）一处判。
 *
 * 位置变更只有两个原语，不可混用：
 * - [LazyListState.requestScrollToItem] —— 登记待生效位置、由下一次**测量**采用，内容增长
 *   与位置更新同帧完成。钉底传越界索引，clamp 后停在列表末尾的 1dp 锚点行。
 * - `animateScrollToItem`/`scrollToItem` —— 导航按钮跳转，走 `scroll {}`，用户手势以更高的
 *   MutatePriority 自动抢占。混用会自伤：`requestScrollToItem` 在 `isScrollInProgress` 时
 *   自己 `launch { scroll {} }` 抢占，放进 `scroll {}` 块里等于取消自己。
 */
@Stable
internal class ChatScrollController(
    val listState: LazyListState,
    private val scope: CoroutineScope,
) {

    /** ChatScreen 每次重组刷新的外部依赖（等价 `rememberUpdatedState`，不驱动重组）。 */
    internal class Inputs {
        /** 生成中**且**目标是末条消息。重答中间消息时原地生成，视口不动。 */
        var generatingAtEnd: Boolean = false
        var hasMessages: Boolean = false
        /** 消息条数，**不含**列表末尾的 1dp 底部锚点行。 */
        var messageCount: Int = 0
        /** 宽容的贴底判定，容得下 reasoning 行一帧内插入把底部顶出视口。 */
        var bottomSlackPx: Float = 0f
        /** 恢复跟随用的"触底"判定，只留一帧流式增长的余量。 */
        var touchBottomSlackPx: Float = 0f
    }

    val inputs = Inputs()

    var autoFollow by mutableStateOf(true)
        private set

    /** 悬浮导航按钮栏是否可见（滚动时出现，静止 [NavButtonsHideDelayMs] 毫秒后隐藏）。 */
    var showNavButtons by mutableStateOf(false)
        private set

    /** 当前消息导航按钮显示模式（偏好设置，每次重组由 ChatScreen 刷入 inputs） */
    var navButtonsMode: NavButtonsMode = NavButtonsMode.ON_SCROLL

    /** 手指落下到滚动完全静止（含惯性）为止。 */
    private var dragging = false
    private var dragStart: Pair<Int, Int>? = null

    /** 连续点"上/下一条"的链式锚点，手指一碰即失效。 */
    private var navAnchorIndex: Int? = null

    private var pinJob: Job? = null
    private var navJob: Job? = null
    private var navHideJob: Job? = null
    private val pinMutex = MutatorMutex()

    // ── 几何 ────────────────────────────────────────────────────────────────

    fun isAtBottom(slackPx: Float = inputs.bottomSlackPx): Boolean {
        val layout = listState.layoutInfo
        if (layout.totalItemsCount == 0) return true
        val last = layout.visibleItemsInfo.lastOrNull() ?: return true
        if (last.index < layout.totalItemsCount - 2) return false
        return last.offset + last.size - layout.viewportEndOffset <= slackPx
    }

    /** 内容是否溢出视口。不足一屏时无处可导航，不显示按钮栏。 */
    fun contentOverflows(): Boolean {
        if (listState.layoutInfo.totalItemsCount == 0) return false
        return listState.canScrollForward || listState.firstVisibleItemIndex > 0 ||
            listState.firstVisibleItemScrollOffset > 0
    }

    private fun isExactlyAtBottom(): Boolean {
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull() ?: return false
        return last.index == info.totalItemsCount - 1 &&
            last.offset + last.size <= info.viewportEndOffset
    }

    private fun currentPosition() =
        listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset

    // ── 到底部 ──────────────────────────────────────────────────────────────

    /** 单帧钉底，不收敛。流式跟随与 IME 高度变化用。 */
    fun snapToBottom() {
        // 惯性期间让位：requestScrollToItem 会抢占取消 fling，把手指甩出的惯性硬截停
        if (dragging && listState.isScrollInProgress) return
        listState.requestScrollToItem(listState.layoutInfo.totalItemsCount + BottomOvershoot)
    }

    /**
     * 收敛式钉底：先同帧登记一次（否则调用点这帧会先画出旧位置），再逐帧重试直到连续
     * [StableFrames] 帧精确贴底 —— Markdown 异步解析，远距离跳转后新组合的消息要过几帧
     * 才长到真实高度，钉一次会停在半路。
     *
     * [pinJob] + [pinMutex] 保证同时只有一个收敛循环，否则多个循环各自钉底、各自计稳定
     * 帧数，互相打架。
     */
    fun pinToBottom() {
        pinJob?.cancel()
        snapToBottom()
        pinJob = scope.launch {
            pinMutex.mutate(MutatePriority.Default) { convergeToBottom() }
        }
    }

    /** 取消在途的程序化滚动（钉底 + 导航跳转）。 */
    fun cancelProgrammaticScroll() {
        pinJob?.cancel()
        pinJob = null
        navJob?.cancel()
        navJob = null
    }

    private suspend fun convergeToBottom() {
        var stable = 0
        var frames = 0
        while (frames++ < MaxPinFrames && stable < StableFrames) {
            if (dragging && listState.isScrollInProgress) return
            if (isExactlyAtBottom()) {
                stable++
            } else {
                stable = 0
                listState.requestScrollToItem(listState.layoutInfo.totalItemsCount + BottomOvershoot)
            }
            withFrameNanos { }
        }
    }

    private fun anchorTo(index: Int, offsetPx: Int) {
        listState.requestScrollToItem(index, -offsetPx)
    }

    /**
     * 切分支后重新锚定：内容一换，旧的像素锚定会落在新文本的任意位置。
     *
     * - 末条或人在底部附近：钉住底部锚点，长短变化全发生在锚点上方，切换按钮到输入栏的距离
     *   纹丝不动，也不会触发 LazyColumn"填满视口"的回拉。
     * - 其余：钉住**下一条**消息的顶部（即本条底部/切换按钮行）。不钉本条顶部——那个偏移与
     *   本条新高度相关，滚进长消息内部后切到更短的分支时会越界、被归一化到任意位置。
     */
    fun switchAnchored(index: Int, isLast: Boolean, switch: () -> Unit) {
        // 生成刚结束的收敛循环可能还在跑，不作废的话下面的锚定会被它拽回底部
        cancelProgrammaticScroll()
        val layoutBefore = listState.layoutInfo
        val selfInfo = layoutBefore.visibleItemsInfo.firstOrNull { it.index == index }
        // index+1 可见用实测顶部；不可见（按钮贴着视口底）用本条底 + 间距推算
        val nextTop = layoutBefore.visibleItemsInfo.firstOrNull { it.index == index + 1 }?.offset
            ?: selfInfo?.let { it.offset + it.size + layoutBefore.mainAxisItemSpacing }
        val nearBottom = isAtBottom()

        switch()

        if (isLast || nearBottom) {
            val info = listState.layoutInfo
            val anchor = info.visibleItemsInfo.lastOrNull()
                ?.takeIf { it.index == info.totalItemsCount - 1 }
            if (anchor != null) anchorTo(anchor.index, anchor.offset) else pinToBottom()
        } else if (nextTop != null) {
            anchorTo(index + 1, nextTop)
        }
    }

    // ── 悬浮导航按钮栏 ──────────────────────────────────────────────────────

    fun revealNavButtons() {
        if (navButtonsMode == NavButtonsMode.NEVER) return
        if (!contentOverflows()) return
        showNavButtons = true
        navHideJob?.cancel()
        // 始终显示模式不安排隐藏；滚动显示模式静止 [NavButtonsHideDelayMs] 毫秒后隐藏
        if (navButtonsMode == NavButtonsMode.ON_SCROLL) {
            navHideJob = scope.launch {
                delay(NavButtonsHideDelayMs)
                showNavButtons = false
            }
        }
    }

    /** 长会话里动画穿过全部消息没有意义，直接定位。 */
    fun scrollToTop() {
        revealNavButtons()
        cancelProgrammaticScroll()
        autoFollow = false
        navAnchorIndex = 0
        navJob = scope.launch { listState.scrollToItem(0) }
    }

    /**
     * 跳到相邻的上/下一条消息。锚点优先用 [navAnchorIndex]，连续点按时逐条推进，
     * 不受动画途中 firstVisibleItemIndex 尚未落位的影响。
     */
    fun jumpToAdjacentMessage(forward: Boolean) {
        revealNavButtons()
        val count = inputs.messageCount
        if (count <= 0) return
        val anchor = (navAnchorIndex ?: listState.firstVisibleItemIndex).coerceIn(0, count - 1)
        val target = anchor + if (forward) 1 else -1
        if (target !in 0 until count) return
        cancelProgrammaticScroll()
        autoFollow = false
        navAnchorIndex = target
        navJob = scope.launch { listState.animateScrollToItem(target) }
    }

    fun scrollToBottom() {
        revealNavButtons()
        navAnchorIndex = null
        autoFollow = true
        pinToBottom()
    }

    // ── 事件 ────────────────────────────────────────────────────────────────

    /** 发送、重试、删除末条：回到底部并重置。 */
    fun onSendOrReset() {
        if (!inputs.hasMessages) return
        navAnchorIndex = null
        autoFollow = true
        pinToBottom()
    }

    fun onSessionOpened() {
        navAnchorIndex = null
        autoFollow = true
        if (inputs.hasMessages) pinToBottom()
    }

    /** 生成结束：仍在跟随则钉住底部（正文切 Markdown、工具栏出现会改高度）。 */
    fun onGenerationFinished() {
        if (inputs.hasMessages && autoFollow) pinToBottom()
    }

    // ── 循环（由 ChatScreen 在聊天区域在屏期间驱动） ──────────────────────────

    suspend fun runLoops(): Unit = coroutineScope {
        // 上次若在惯性途中切走全屏页，settle 来不及跑就被拆掉，dragging 会挂着陈旧的 true
        dragging = false
        launch { runDragLoop() }
        launch { runAutoFollowLoop() }
    }

    /** 用 [DragInteraction] 而非 `isScrollInProgress` 判定"用户在滚"，排除程序化滚动。 */
    private suspend fun runDragLoop(): Unit = coroutineScope {
        launch {
            listState.interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is DragInteraction.Start -> {
                        dragging = true
                        dragStart = currentPosition()
                        navAnchorIndex = null
                        cancelProgrammaticScroll()
                        revealNavButtons()
                        autoFollow = false
                    }
                    is DragInteraction.Stop, is DragInteraction.Cancel -> {
                        // 零位移拖拽不经历 isScrollInProgress 翻转，下面的收集器不触发
                        if (!listState.isScrollInProgress) settleDrag()
                    }
                    else -> {}
                }
            }
        }
        launch {
            snapshotFlow { listState.isScrollInProgress }.collect { if (!it) settleDrag() }
        }
    }

    /**
     * 滚动彻底停下时结算跟随：本次手势净位移朝上就停跟，否则落点触底才跟随。
     *
     * 必须在这一处判完再清 [dragging]：拆成一个订阅布局变化的循环连续改写不行——它与清
     * [dragging] 的收集器订阅同一批快照失效，后者先注册先恢复，最后一次落点永远读不到。
     */
    private fun settleDrag() {
        if (!dragging) return
        dragging = false
        // 隐藏倒计时从"真正静止"起算，长距离惯性途中按钮栏不会先消失
        revealNavButtons()
        val start = dragStart ?: return
        val now = currentPosition()
        val scrolledUp = now.first < start.first ||
            (now.first == start.first && now.second < start.second)
        autoFollow = !scrolledUp && isAtBottom(inputs.touchBottomSlackPx)
    }

    /**
     * 流式跟随：每次布局后若仍在跟随则钉底（`layoutInfo` 每次测量都是新实例，这个
     * snapshotFlow 等价于"每次布局后回调一次"）。
     *
     * 判据刻意不含"生成中的消息是否在渲染列表里"：分页窗口未抵达底部时它会暂时不在，
     * 拿它当条件会漏判。
     */
    private suspend fun runAutoFollowLoop() {
        snapshotFlow { listState.layoutInfo }.collect {
            if (!inputs.generatingAtEnd || !autoFollow || listState.isScrollInProgress) {
                return@collect
            }
            listState.requestScrollToItem(it.totalItemsCount + BottomOvershoot)
        }
    }

    private companion object {
        /** 越界索引会被 clamp 回可达的最大位置，从而稳定停在列表末尾的锚点行上。 */
        const val BottomOvershoot = 5
        const val MaxPinFrames = 60
        const val StableFrames = 3
        const val NavButtonsHideDelayMs = 2000L
    }
}

/**
 * **必须调用在全屏页面切换（`when (nav.lastOrNull())` + return）之上**：命中全屏分支时整个
 * 聊天区域离开组合，其内 remember 状态全毁，返回时滚动位置丢失、副作用误触发钉底。
 *
 * 只提升状态，不提升 [ChatScrollController.runLoops]：那些循环应随聊天区域在屏/离屏启停。
 */
@Composable
internal fun rememberChatScrollController(
    listState: LazyListState = rememberLazyListState(),
): ChatScrollController {
    val scope = rememberCoroutineScope()
    return remember(listState, scope) { ChatScrollController(listState, scope) }
}
