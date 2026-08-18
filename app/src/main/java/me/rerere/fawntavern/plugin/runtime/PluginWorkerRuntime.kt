package me.rerere.fawntavern.plugin.runtime

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.define
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One QuickJS engine owned by the isolated worker process. */
internal class PluginWorkerRuntime(
    private val pluginId: String,
    private val source: String,
    private val entryName: String,
    private val hostCall: suspend (pluginId: String, sessionId: String, method: String, paramsJson: String) -> String,
) {
    private val dispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "plugin-$pluginId").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private lateinit var engine: QuickJs
    private var currentSessionId = ""

    suspend fun initialize() = withContext(dispatcher) {
        check(source.toByteArray(Charsets.UTF_8).size <= MAX_SOURCE_BYTES) { "插件入口超过 384KB" }
        engine = QuickJs.create(dispatcher).apply {
            memoryLimit = MEMORY_LIMIT_BYTES
            maxStackSize = MAX_STACK_BYTES
        }
        engine.define("__ftHost") {
            asyncFunction("call") { args ->
                val method = args.getOrNull(0)?.toString().orEmpty()
                val params = args.getOrNull(1)?.toString() ?: "null"
                require(method in ALLOWED_HOST_METHODS) { "宿主方法不可用: $method" }
                hostCall(pluginId, currentSessionId, method, params)
            }
        }
        engine.evaluate<Any?>(BOOTSTRAP, "fawntavern-bootstrap.js", asModule = false)
        engine.evaluate<Any?>(source, entryName, asModule = true)
    }

    suspend fun invoke(
        capability: String,
        method: String,
        argumentJson: String,
        configJson: String,
    ): String = withContext(dispatcher) {
        require(argumentJson.toByteArray(Charsets.UTF_8).size <= MAX_ARGUMENT_BYTES) { "插件调用参数过大" }
        val argument = JSONObject(argumentJson)
        currentSessionId = argument.optString("sessionId")
        try {
            val payload = JSONObject()
                .put("capability", capability)
                .put("method", method)
                .put("argument", argument)
                .put("config", parseObject(configJson))
            val quoted = JSONObject.quote(payload.toString())
            engine.evaluate<Any?>("globalThis.__ftCall($quoted)", "fawntavern-call.js", asModule = false)
            val result = engine.evaluate<String?>("globalThis.__ft.result", "fawntavern-result.js", asModule = false)
                ?: error("插件没有返回调用结果")
            check(result.toByteArray(Charsets.UTF_8).size <= MAX_RESULT_BYTES) { "插件返回结果过大" }
            result
        } finally {
            currentSessionId = ""
        }
    }

    suspend fun close() {
        withContext(dispatcher) {
            if (::engine.isInitialized) runCatching { engine.close() }
        }
        dispatcher.close()
    }

    private fun parseObject(json: String): JSONObject =
        runCatching { JSONObject(json) }.getOrElse { JSONObject() }

    private companion object {
        const val MAX_SOURCE_BYTES = 384 * 1024
        const val MAX_ARGUMENT_BYTES = 192 * 1024
        const val MAX_RESULT_BYTES = 256 * 1024
        const val MEMORY_LIMIT_BYTES = 16L * 1024 * 1024
        const val MAX_STACK_BYTES = 512L * 1024
        val ALLOWED_HOST_METHODS = setOf("state.save", "log")

        val BOOTSTRAP = """
            globalThis.__ft = { handlers: {}, result: null, config: {}, context: {} };

            function __ftParse(raw, fallback) {
              try { return JSON.parse(raw); } catch (_) { return fallback; }
            }

            function __ftHostCall(method, params) {
              return globalThis.__ftHost.call(method, JSON.stringify(params == null ? null : params))
                .then(function (raw) { return __ftParse(raw, null); });
            }

            var api = {
              register: function (handlers) {
                Object.assign(globalThis.__ft.handlers, handlers || {});
              },
              loadState: async function () {
                var value = globalThis.__ft.context.extState;
                return value === undefined ? null : value;
              },
              saveState: function (value) {
                return __ftHostCall('state.save', { value: value }).then(function () {
                  globalThis.__ft.context.extState = value == null ? null : value;
                  return null;
                });
              },
              log: function (message, level) {
                return __ftHostCall('log', { message: String(message), level: level || 'info' });
              }
            };
            Object.defineProperty(api, 'config', { get: function () { return globalThis.__ft.config; } });
            globalThis.FawnTavern = Object.freeze(api);

            function __ftFinish(ok, value) {
              try {
                globalThis.__ft.result = JSON.stringify(ok
                  ? { ok: true, value: value === undefined ? null : value }
                  : { ok: false, error: String((value && value.message) || value) });
              } catch (error) {
                globalThis.__ft.result = JSON.stringify({ ok: false, error: 'result-not-serializable' });
              }
            }

            globalThis.__ftCall = function (raw) {
              globalThis.__ft.result = null;
              var request = JSON.parse(raw);
              globalThis.__ft.config = request.config || {};
              globalThis.__ft.context = request.argument || {};
              var handler = globalThis.__ft.handlers[request.capability];
              var fn = handler && handler[request.method];
              if (typeof fn !== 'function') {
                __ftFinish(false, 'no-such-handler');
                return;
              }
              try {
                var returned = fn(globalThis.__ft.context);
                if (returned && typeof returned.then === 'function') {
                  returned.then(function (value) { __ftFinish(true, value); },
                    function (error) { __ftFinish(false, error); });
                } else {
                  __ftFinish(true, returned);
                }
              } catch (error) {
                __ftFinish(false, error);
              }
            };
        """.trimIndent()
    }
}
