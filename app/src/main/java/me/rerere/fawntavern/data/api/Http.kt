package me.rerere.fawntavern.data.api

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/** 全局 OkHttp 客户端：连接池/线程池全应用共享，SSE 用长读超时的派生实例 */
internal object Http {

    private val cleartextHosts = setOf("localhost", "127.0.0.1", "10.0.2.2")

    internal fun permitsCleartext(host: String): Boolean = host.lowercase() in cleartextHosts

    /** 普通请求（模型列表、余额查询等） */
    val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            if (!request.url.isHttps && !permitsCleartext(request.url.host)) {
                throw IOException("Cleartext HTTP is only allowed for localhost endpoints")
            }
            chain.proceed(request)
        }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** SSE 流式请求：模型思考期间可能长时间无数据，读超时放宽到 5 分钟 */
    val sseClient: OkHttpClient = client.newBuilder()
        .readTimeout(300, TimeUnit.SECONDS)
        .build()
}
