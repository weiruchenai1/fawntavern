package me.rerere.fawntavern.extension

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 扩展注册表（无 DI，仿项目其余单例 `object` 风格；不依赖 Android/Context）。
 *
 * - 内置扩展通过 [register] 登记；第三方 JS 插件由 PluginManager 扫盘后登记。
 * - 只负责"有哪些扩展"，不负责"哪些启用"——启用状态属用户设置，见 [ExtensionStore]。
 * - 能力查询由消费方按能力接口 `filterIsInstance<T>()` 完成（如 [PromptContributor] / [GenerationLifecycle]）。
 */
object ExtensionHost {
    private val lock = Any()
    private val _extensions = MutableStateFlow<List<Extension>>(emptyList())
    val extensions: StateFlow<List<Extension>> = _extensions.asStateFlow()

    /** 登记一个扩展（按 id 幂等去重）。 */
    fun register(ext: Extension) {
        synchronized(lock) {
            if (_extensions.value.none { it.info.id == ext.info.id }) {
                _extensions.value = _extensions.value + ext
            }
        }
    }

    /** 全部已登记扩展（含未启用）。 */
    fun all(): List<Extension> = _extensions.value

    fun byId(id: String): Extension? = _extensions.value.firstOrNull { it.info.id == id }

    /** 注销（插件卸载）；无此 id 时静默。 */
    fun unregister(id: String) {
        synchronized(lock) {
            _extensions.value = _extensions.value.filterNot { it.info.id == id }
        }
    }

    /** 原地替换（插件更新为同 id 新实例）；未注册时等价 register。 */
    fun replace(ext: Extension) {
        synchronized(lock) {
            val current = _extensions.value
            val idx = current.indexOfFirst { it.info.id == ext.info.id }
            _extensions.value = if (idx >= 0) {
                current.toMutableList().apply { set(idx, ext) }
            } else {
                current + ext
            }
        }
    }
}
