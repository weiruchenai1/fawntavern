package me.rerere.fawntavern.data

import android.annotation.SuppressLint
import android.content.SharedPreferences

/** KTX 会丢弃 commit() 结果，关键同步写入需要保留该成功信号。 */
@SuppressLint("UseKtx")
inline fun SharedPreferences.commitChanges(
    action: SharedPreferences.Editor.() -> Unit,
): Boolean {
    val editor = edit()
    action(editor)
    return editor.commit()
}
