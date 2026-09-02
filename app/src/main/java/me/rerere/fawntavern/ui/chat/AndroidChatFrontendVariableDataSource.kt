package me.rerere.fawntavern.ui.chat

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.fawntavern.data.settings.FrontendVariableStore

internal class AndroidChatFrontendVariableDataSource(context: Context) : ChatFrontendVariableDataSource {
    private val appContext = context.applicationContext

    override suspend fun load(scope: String, ownerId: String): String = withContext(Dispatchers.IO) {
        FrontendVariableStore.load(appContext, scope, ownerId)
    }

    override suspend fun save(scope: String, ownerId: String, json: String) = withContext(Dispatchers.IO) {
        FrontendVariableStore.save(appContext, scope, ownerId, json)
    }
}
