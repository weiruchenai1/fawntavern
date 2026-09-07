package me.rerere.fawntavern.core.resource

/** 大草稿保存在独立文件中，Android 状态恢复只携带草稿键。 */
interface EditorDraftStorage {
    suspend fun read(key: String): String?
    suspend fun write(key: String, value: String)
    suspend fun remove(key: String)
}
