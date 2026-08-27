package me.rerere.fawntavern.extension

/** 一条快捷回复；[send] 表示点击后直接发送，否则只插入输入框。 */
data class QuickReply(
    val label: String,
    val text: String,
    val send: Boolean = false,
)

/** 向聊天输入区提供快捷回复的扩展能力。 */
interface QuickReplyProvider {
    fun quickReplies(config: String): List<QuickReply>
}
