package me.rerere.stapp.extension

/** 一条快捷回复：点击后把 [text] 插入输入框（send=false）或直接发送（send=true）。 */
data class QuickReply(
    val label: String,
    val text: String,
    val send: Boolean = false,
)

/** UI 插槽能力：向输入框上方提供快捷回复按钮。[config] 为本扩展的配置 JSON。 */
interface QuickReplyProvider {
    fun quickReplies(config: String): List<QuickReply>
}
