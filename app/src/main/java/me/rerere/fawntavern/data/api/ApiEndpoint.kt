package me.rerere.fawntavern.data.api

internal fun ApiProvider.apiEndpoint(defaultPath: String, modelId: String? = null): String {
    var path = apiPath.trim().ifBlank { defaultPath }
    if (modelId != null) path = path.replace("{model}", modelId)
    return "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"
}
