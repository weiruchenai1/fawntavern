package me.rerere.fawntavern.plugin

import com.dokar.quickjs.QuickJs
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 锁死 alpha13 的一条硬限制：**协程取消打不断正在执行的 JS**。
 *
 * `withTimeout` 超时后 evaluate 不会返回 —— JNI 调用不可取消，QuickJS 的 interrupt handler
 * 在这条路径上不起作用。插件运行时因此必须假定「跑飞的引擎不可回收」：正式运行时放进
 * 独立进程，由进程内 watchdog 在 deadline 到达时结束整个 worker 进程。
 *
 * 这里的死循环跑在 daemon 线程、测试线程有界等待，避免探针自身挂住测试进程。
 * 若某天 quickjs-kt 修复了可中断性，本测试会失败 —— 那时该回来放宽运行时的隔离策略。
 */
class QuickJsInterruptTest {

    @Test
    fun coroutineCancellationDoesNotInterruptJs() {
        val js = QuickJs.create(Dispatchers.Default)
        val pool = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }
        try {
            val returned = CompletableFuture<Boolean>()
            pool.execute {
                runCatching { runBlocking { withTimeout(500) { js.evaluate<Unit>("while(true){}") } } }
                returned.complete(true)
            }
            val came = runCatching { returned.get(5, TimeUnit.SECONDS) }.getOrDefault(false)
            assertFalse("withTimeout 竟然打断了死循环 —— 可中断性已修复，请重新评估插件运行时的隔离策略", came)
        } finally {
            // 不 close：引擎正卡在 JS 里，close 自身也会阻塞
            pool.shutdownNow()
        }
    }
}
