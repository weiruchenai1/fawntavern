package me.rerere.fawntavern.ui.chat

interface ChatFrontendVariableDataSource {
    suspend fun load(scope: String, ownerId: String): String
    suspend fun save(scope: String, ownerId: String, json: String)
}

object EmptyChatFrontendVariableDataSource : ChatFrontendVariableDataSource {
    override suspend fun load(scope: String, ownerId: String): String = "{}"
    override suspend fun save(scope: String, ownerId: String, json: String) = Unit
}
