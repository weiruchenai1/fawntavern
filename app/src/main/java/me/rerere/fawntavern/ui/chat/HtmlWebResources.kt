package me.rerere.fawntavern.ui.chat

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.File
import me.rerere.fawntavern.data.api.Http
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request

/** 只向消息 WebView 暴露随 APK 发布的前端兼容资源。 */
internal object FrontendAssetResources {
    fun intercept(context: Context, request: WebResourceRequest): WebResourceResponse? {
        if (request.method != "GET" || request.url.scheme != "https" ||
            request.url.host != "appassets.androidplatform.net") return null
        val relative = request.url.encodedPath.orEmpty().removePrefix("/frontend/")
        if (relative.isBlank() || relative.contains("..") || relative.startsWith('/')) return null
        val mime = when (relative.substringAfterLast('.', "").lowercase()) {
            "js" -> "application/javascript"
            "css" -> "text/css"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "png" -> "image/png"
            else -> return null
        }
        val stream = runCatching { context.assets.open("frontend/$relative") }.getOrNull() ?: return null
        return WebResourceResponse(mime, if (mime.startsWith("text/") || mime.contains("javascript")) "utf-8" else null, stream)
    }
}

/** 浏览器渲染的 HTTPS 图片专用缓存，不依赖服务器返回的缓存头。 */
internal object HtmlImageResourceCache {
    private const val MaxCacheBytes = 128L * 1024L * 1024L
    private const val CacheMaxAgeSeconds = 30L * 24L * 60L * 60L
    private val imageExtensions = setOf(
        "avif", "bmp", "gif", "heic", "heif", "ico", "jpeg", "jpg", "png", "svg", "webp",
    )

    @Volatile private var client: OkHttpClient? = null

    fun intercept(context: Context, request: WebResourceRequest): WebResourceResponse? {
        if (request.method != "GET" || request.url.scheme != "https" || !isImageRequest(request)) return null
        val networkRequest = Request.Builder()
            .url(request.url.toString())
            .apply {
                request.requestHeaders.forEach { (name, value) ->
                    if (!name.equals("Cache-Control", ignoreCase = true) &&
                        !name.equals("Pragma", ignoreCase = true) &&
                        !name.startsWith("If-", ignoreCase = true)) {
                        header(name, value)
                    }
                }
            }
            .build()
        val response = runCatching { client(context).newCall(networkRequest).execute() }.getOrNull() ?: return null
        if (!response.isSuccessful) {
            response.close()
            return null
        }
        val body = response.body
        val contentType = body.contentType()
        if (contentType?.type != "image") {
            response.close()
            return null
        }
        return WebResourceResponse("${contentType.type}/${contentType.subtype}", null, body.byteStream())
    }

    private fun client(context: Context): OkHttpClient {
        client?.let { return it }
        return synchronized(this) {
            client ?: Http.client.newBuilder()
                .cache(Cache(File(context.applicationContext.cacheDir, "html_image_cache"), MaxCacheBytes))
                .addNetworkInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    if (response.isSuccessful && response.body.contentType()?.type == "image") {
                        response.newBuilder()
                            .removeHeader("Pragma")
                            .header("Cache-Control", "public, max-age=$CacheMaxAgeSeconds")
                            .build()
                    } else response
                }
                .build()
                .also { client = it }
        }
    }

    private fun isImageRequest(request: WebResourceRequest): Boolean {
        val acceptsImages = request.requestHeaders.entries.any { (name, value) ->
            name.equals("Accept", ignoreCase = true) && value.contains("image/", ignoreCase = true)
        }
        if (acceptsImages) return true
        val extension = request.url.lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return extension in imageExtensions
    }
}
