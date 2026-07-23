package me.rerere.fawntavern.extension

/**
 * 扩展注册表（无 DI，仿项目其余单例 `object` 风格；不依赖 Android/Context）。
 *
 * - 内置扩展在 App 初始化时通过 [register] 登记；Phase 2 的第三方 JS 扩展在运行时（加载/启用后）登记。
 * - 只负责"有哪些扩展"，不负责"哪些启用"——启用状态属用户设置，见 [ExtensionStore]。
 * - 能力查询由消费方按能力接口 `filterIsInstance<T>()` 完成（如 [PromptContributor] / [GenerationLifecycle]）。
 */
object ExtensionHost {
    private val registered = mutableListOf<Extension>()

    /** 登记一个扩展（按 id 幂等去重）。 */
    fun register(ext: Extension) {
        if (registered.none { it.info.id == ext.info.id }) registered += ext
    }

    /** 全部已登记扩展（含未启用）。 */
    fun all(): List<Extension> = registered.toList()

    fun byId(id: String): Extension? = registered.firstOrNull { it.info.id == id }
}
