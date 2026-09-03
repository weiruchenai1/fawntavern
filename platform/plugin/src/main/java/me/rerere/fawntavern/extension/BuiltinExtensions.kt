package me.rerere.fawntavern.extension

import me.rerere.fawntavern.extension.builtin.QuickReplyExtension
import me.rerere.fawntavern.extension.builtin.SummarizeExtension

/** 内置官方扩展的登记入口。App 初始化时调用一次（[ExtensionHost.register] 按 id 幂等）。 */
object BuiltinExtensions {
    fun registerAll() {
        ExtensionHost.register(SummarizeExtension)
        ExtensionHost.register(QuickReplyExtension)
    }
}
