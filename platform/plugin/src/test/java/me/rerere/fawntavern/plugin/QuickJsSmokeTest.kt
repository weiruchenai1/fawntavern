package me.rerere.fawntavern.plugin

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * quickjs-kt 冒烟测试：验证 -jvm 变体在 JVM 单测里能跑、API 形态符合预期。
 * 这是整个插件系统最不确定的外部依赖，先钉死再往上堆代码。
 */
class QuickJsSmokeTest {

    /** 普通表达式的有界测试辅助；死循环不可依靠此超时打断，见 QuickJsInterruptTest。 */
    private fun <T> runJs(js: QuickJs, timeoutMs: Long = 5000, block: suspend () -> T): T =
        runBlocking { withTimeout(timeoutMs) { block() } }

    @Test
    fun basicEvaluate() {
        val js = QuickJs.create(Dispatchers.Default)
        try {
            assertEquals(3L, runJs(js) { js.evaluate<Long>("1 + 2") })  // JS number → Long，不是 Int
            assertEquals("hello jack", runJs(js) { js.evaluate<String>("'hello ' + 'jack'") })
        } finally {
            js.close()
        }
    }

    @Test
    fun bindGlobalFunction() {
        val js = QuickJs.create(Dispatchers.Default)
        try {
            js.function("double") { args -> (args.first() as Long) * 2 }
            assertEquals(42L, runJs(js) { js.evaluate<Long>("double(21)") })
        } finally {
            js.close()
        }
    }

    @Test
    fun bindObjectScope() {
        val js = QuickJs.create(Dispatchers.Default)
        try {
            js.define("svc") {
                function("echo") { args -> args.first().toString() }
            }
            assertEquals("hi", runJs(js) { js.evaluate<String>("svc.echo('hi')") })
        } finally {
            js.close()
        }
    }

    @Test
    fun esModuleWithAddModule() {
        val js = QuickJs.create(Dispatchers.Default)
        try {
            js.addModule(name = "math", code = "export function add(a, b) { return a + b; }")
            var result = 0L
            js.function("__set") { args -> result = args.first() as Long }
            runJs(js) {
                js.evaluate<Any?>("import * as m from \"math\"; __set(m.add(40, 2));", "entry.js", asModule = true)
            }
            assertEquals(42L, result)
        } finally {
            js.close()
        }
    }

    /** 内存超限必须能中止分配循环 —— 这是跑飞插件唯一还起作用的兜底（取消打不断 JS，见 QuickJsInterruptTest）。 */
    @Test
    fun memoryLimitEnforced() {
        val pool = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }
        val threw = CompletableFuture<Boolean>()
        try {
            pool.execute {
                val js = QuickJs.create(Dispatchers.Default)
                js.memoryLimit = 64 * 1024 // 64KB，足够小到一分配就爆
                threw.complete(
                    try {
                        runJs(js) { js.evaluate<Any?>("let a = []; while(true){ a.push('x'.repeat(1024)); }") }
                        false
                    } catch (_: Exception) {
                        true
                    }
                )
            }
            assertTrue("内存超限未中止分配循环", runCatching { threw.get(15, TimeUnit.SECONDS) }.getOrDefault(false))
        } finally {
            pool.shutdownNow()
        }
    }
}
