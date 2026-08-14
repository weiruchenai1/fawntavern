package me.rerere.fawntavern.data

import android.annotation.SuppressLint
import android.content.SharedPreferences

/** KTX intentionally discards commit()'s result; critical durable writes need that signal. */
@SuppressLint("UseKtx")
internal inline fun SharedPreferences.commitChanges(
    action: SharedPreferences.Editor.() -> Unit,
): Boolean {
    val editor = edit()
    action(editor)
    return editor.commit()
}
