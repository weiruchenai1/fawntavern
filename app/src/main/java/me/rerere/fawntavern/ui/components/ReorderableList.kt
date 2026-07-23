package me.rerere.fawntavern.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * 长按拖拽排序的通用状态（基于 sh.calvin.reorderable）。
 *
 * 库回调给的 `from.key`/`to.key` 是列表项 key，据此在数据列表里反查真实下标做换位，
 * 从而不受列表头部/尾部非数据项（如占位 Spacer）影响，避免下标错位越界。
 *
 * @param items 当前数据列表
 * @param keyOf 取某项的稳定 key（须与 `itemsIndexed(key = ...)` 一致）
 * @param onReorder 换位后回调新列表（调用方负责落盘）
 */
@Composable
fun <T> rememberReorderableList(
    items: List<T>,
    keyOf: (T) -> Any,
    listState: LazyListState = rememberLazyListState(),
    onReorder: (List<T>) -> Unit,
): Pair<LazyListState, ReorderableLazyListState> {
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val list = items.toMutableList()
        val fromIdx = list.indexOfFirst { keyOf(it) == from.key }
        val toIdx = list.indexOfFirst { keyOf(it) == to.key }
        if (fromIdx < 0 || toIdx < 0) return@rememberReorderableLazyListState
        list.add(toIdx, list.removeAt(fromIdx))
        onReorder(list)
    }
    return listState to reorderState
}
