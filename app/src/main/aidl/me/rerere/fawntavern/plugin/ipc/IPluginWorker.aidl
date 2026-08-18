package me.rerere.fawntavern.plugin.ipc;

import me.rerere.fawntavern.plugin.ipc.IPluginWorkerCallback;

oneway interface IPluginWorker {
    void setCallback(IPluginWorkerCallback callback);
    void loadPlugin(String requestId, String pluginId, String source, String entryName, long timeoutMs);
    void invoke(
        String requestId,
        String pluginId,
        String capability,
        String method,
        String argumentJson,
        String configJson,
        long timeoutMs
    );
    void unloadPlugin(String pluginId);
    void resolveHostCall(String requestId, boolean ok, String payloadJson);
}
