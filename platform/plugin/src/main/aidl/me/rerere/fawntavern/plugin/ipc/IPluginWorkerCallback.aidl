package me.rerere.fawntavern.plugin.ipc;

oneway interface IPluginWorkerCallback {
    void onResult(String requestId, String pluginId, boolean ok, String payloadJson);
    void onHostCall(
        String requestId,
        String pluginId,
        String sessionId,
        String method,
        String paramsJson
    );
}
