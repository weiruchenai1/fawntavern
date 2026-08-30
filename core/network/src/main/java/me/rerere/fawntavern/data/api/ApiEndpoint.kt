package me.rerere.fawntavern.data.api

internal fun ApiProvider.apiEndpoint(defaultPath: String, modelId: String? = null): String {
    var path = apiPath.trim().ifBlank { defaultPath }
    if (modelId != null) path = path.replace("{model}", modelId)
    return "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"
}

internal fun ApiProvider.openAiChatEndpoint(): String {
    val legacy = apiPath.takeIf { type == "openai" && !useResponseApi }.orEmpty()
    return endpointFor(chatApiPath.ifBlank { legacy }, "/chat/completions")
}

internal fun ApiProvider.imageGenerationEndpoint(edit: Boolean): String = if (edit) {
    endpointFor(imageEditApiPath, "/images/edits")
} else {
    endpointFor(imageGenerationApiPath, "/images/generations")
}

private fun ApiProvider.endpointFor(configuredPath: String, defaultPath: String): String {
    val path = configuredPath.trim().ifBlank { defaultPath }
    return "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"
}
