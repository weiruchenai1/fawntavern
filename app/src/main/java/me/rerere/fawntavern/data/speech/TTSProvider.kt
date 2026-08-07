package me.rerere.fawntavern.data.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.rerere.fawntavern.data.api.Http
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** 音频格式：决定播放前是否需要转码 / 文件扩展名 */
enum class AudioFormat { MP3, WAV }

/** provider 合成结果：一段完整音频字节 + 格式 */
data class TTSResponse(val data: ByteArray, val format: AudioFormat)

/** TTS provider 接口：给定文本产出完整音频 */
interface TTSProvider<T : TTSProviderSetting> {
    suspend fun generateSpeech(context: Context, setting: T, text: String): TTSResponse
}

/** 分发：按配置实例取对应实现（无 DI 的静态分发） */
@Suppress("UNCHECKED_CAST")
fun createTtsProvider(setting: TTSProviderSetting): TTSProvider<TTSProviderSetting> = when (setting) {
    is TTSProviderSetting.SystemTTS -> SystemTTSProvider
    is TTSProviderSetting.OpenAI -> OpenAITTSProvider
    is TTSProviderSetting.Groq -> GroqTTSProvider
    is TTSProviderSetting.XAI -> XAITTSProvider
    is TTSProviderSetting.Gemini -> GeminiTTSProvider
    is TTSProviderSetting.MiniMax -> MiniMaxTTSProvider
    is TTSProviderSetting.Qwen -> QwenTTSProvider
    is TTSProviderSetting.MiMo -> MiMoTTSProvider
} as TTSProvider<TTSProviderSetting>

private const val TAG = "TTSProvider"

/** 把 16-bit 单声道 PCM 裸流包成 WAV（AudioPlayer 只认 wav/mp3，Gemini/Qwen/MiMo 返回的是 PCM） */
internal fun pcm16ToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
    val byteRate = sampleRate * 2
    val blockAlign = 2
    val header = ByteArray(44)
    header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
    writeIntLE(header, 4, 36 + pcm.size)
    header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
    header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
    writeIntLE(header, 16, 16)
    writeShortLE(header, 20, 1)               // PCM
    writeShortLE(header, 22, 1)               // 单声道
    writeIntLE(header, 24, sampleRate)
    writeIntLE(header, 28, byteRate)
    writeShortLE(header, 32, blockAlign)
    writeShortLE(header, 34, 16)              // 位深
    header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
    writeIntLE(header, 40, pcm.size)
    return header + pcm
}

private fun writeIntLE(buf: ByteArray, offset: Int, value: Int) {
    buf[offset] = (value and 0xFF).toByte()
    buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    buf[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    buf[offset + 3] = ((value ushr 24) and 0xFF).toByte()
}

private fun writeShortLE(buf: ByteArray, offset: Int, value: Int) {
    buf[offset] = (value and 0xFF).toByte()
    buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
}

/** 解析 OpenAI 兼容 /audio/speech 响应（Groq/MiMo 等），出错即抛 */
private fun okHttpAudioResponse(client: okhttp3.OkHttpClient, request: Request): ByteArray {
    val response = client.newCall(request).execute()
    if (!response.isSuccessful) throw Exception("TTS 请求失败：${response.code} ${response.message}")
    return response.body.bytes()
}

/** Android 系统 TTS：synthesizeToFile 合成到临时 wav，读回字节 */
object SystemTTSProvider : TTSProvider<TTSProviderSetting.SystemTTS> {
    override suspend fun generateSpeech(
        context: Context, setting: TTSProviderSetting.SystemTTS, text: String,
    ): TTSResponse = withContext(Dispatchers.IO) {
        val audio = suspendCancellableCoroutine<ByteArray> { cont ->
            var tts: TextToSpeech? = null
            val listener = TextToSpeech.OnInitListener { status ->
                if (status != TextToSpeech.SUCCESS) {
                    if (cont.isActive) cont.resumeWithException(Exception("系统 TTS 引擎初始化失败"))
                    return@OnInitListener
                }
                val ttsInstance = tts ?: error("TextToSpeech instance is null")
                val locale = Locale.getDefault()
                val langResult = ttsInstance.setLanguage(locale)
                if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "generateSpeech: Language $locale not supported")
                }
                ttsInstance.setSpeechRate(setting.speechRate)
                ttsInstance.setPitch(setting.pitch)

                val audioFile = File(context.cacheDir, "tts_sys_${System.currentTimeMillis()}.wav")
                val utteranceId = UUID.randomUUID().toString()
                ttsInstance.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun onStart(utteranceId: String?) {}

                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun onDone(utteranceId: String?) {
                        try {
                            if (audioFile.exists()) {
                                val data = audioFile.readBytes()
                                audioFile.delete()
                                if (cont.isActive) cont.resume(data)
                            } else if (cont.isActive) {
                                cont.resumeWithException(Exception("系统 TTS 未生成音频文件"))
                            }
                        } catch (e: Exception) {
                            if (cont.isActive) cont.resumeWithException(e)
                        } finally {
                            ttsInstance.shutdown()
                        }
                    }

                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun onError(utteranceId: String?) {
                        audioFile.delete()
                        if (cont.isActive) cont.resumeWithException(Exception("系统 TTS 合成失败"))
                        ttsInstance.shutdown()
                    }
                })

                val result = ttsInstance.synthesizeToFile(text, null, audioFile, utteranceId)
                if (result != TextToSpeech.SUCCESS) {
                    if (cont.isActive) cont.resumeWithException(Exception("系统 TTS 启动合成失败"))
                    ttsInstance.shutdown()
                }
            }
            tts = TextToSpeech(context.applicationContext, listener)
            cont.invokeOnCancellation { tts.shutdown() }
        }
        TTSResponse(audio, AudioFormat.WAV)
    }
}

/** OpenAI 语音 API（/audio/speech），输出 MP3 */
object OpenAITTSProvider : TTSProvider<TTSProviderSetting.OpenAI> {
    private val client = Http.client.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()

    override suspend fun generateSpeech(
        context: Context, setting: TTSProviderSetting.OpenAI, text: String,
    ): TTSResponse = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("model", setting.model)
            put("input", text)
            put("voice", setting.voice)
            put("response_format", "mp3")
        }
        val request = Request.Builder()
            .url("${setting.baseUrl.trimEnd('/')}/audio/speech")
            .addHeader("Authorization", "Bearer ${setting.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        TTSResponse(okHttpAudioResponse(client, request), AudioFormat.MP3)
    }
}

/** Groq TTS（OpenAI 兼容 /audio/speech），输出 WAV */
object GroqTTSProvider : TTSProvider<TTSProviderSetting.Groq> {
    private val client = Http.client.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()

    override suspend fun generateSpeech(
        context: Context, setting: TTSProviderSetting.Groq, text: String,
    ): TTSResponse = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("model", setting.model)
            put("input", text)
            put("voice", setting.voice)
            put("response_format", "wav")
        }
        val request = Request.Builder()
            .url("${setting.baseUrl.trimEnd('/')}/audio/speech")
            .addHeader("Authorization", "Bearer ${setting.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        TTSResponse(okHttpAudioResponse(client, request), AudioFormat.WAV)
    }
}

/** xAI TTS（/tts 端点），输出 MP3 */
object XAITTSProvider : TTSProvider<TTSProviderSetting.XAI> {
    private val client = Http.client.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()

    override suspend fun generateSpeech(
        context: Context, setting: TTSProviderSetting.XAI, text: String,
    ): TTSResponse = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("text", text)
            put("voice_id", setting.voiceId)
            put("language", setting.language)
        }
        val request = Request.Builder()
            .url("${setting.baseUrl.trimEnd('/')}/tts")
            .addHeader("Authorization", "Bearer ${setting.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        TTSResponse(okHttpAudioResponse(client, request), AudioFormat.MP3)
    }
}

/** Gemini TTS（generateContent），返回 24kHz PCM，包成 WAV */
object GeminiTTSProvider : TTSProvider<TTSProviderSetting.Gemini> {
    private val client = Http.client.newBuilder().readTimeout(30, TimeUnit.SECONDS).build()

    override suspend fun generateSpeech(
        context: Context, setting: TTSProviderSetting.Gemini, text: String,
    ): TTSResponse = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", text) })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().apply { put("AUDIO") })
                put("speechConfig", JSONObject().apply {
                    put("voiceConfig", JSONObject().apply {
                        put("prebuiltVoiceConfig", JSONObject().apply { put("voiceName", setting.voiceName) })
                    })
                })
            })
            put("model", setting.model)
        }
        val request = Request.Builder()
            .url("${setting.baseUrl.trimEnd('/')}/models/${setting.model}:generateContent")
            .addHeader("x-goog-api-key", setting.apiKey)
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw Exception("TTS 请求失败：${response.code} ${response.message}")
        val resp = JSONObject(response.body.string())
        val audioBase64 = resp.optJSONArray("candidates")
            ?.optJSONObject(0)?.optJSONObject("content")
            ?.optJSONArray("parts")?.optJSONObject(0)
            ?.optJSONObject("inlineData")?.optString("data")
            ?: throw Exception("Gemini TTS 未返回音频")
        TTSResponse(pcm16ToWav(Base64.decode(audioBase64, Base64.DEFAULT), 24000), AudioFormat.WAV)
    }
}

/** MiniMax TTS（t2a_v2 非流式），返回 JSON 内 base64 MP3 */
object MiniMaxTTSProvider : TTSProvider<TTSProviderSetting.MiniMax> {
    private val client = Http.client.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()

    override suspend fun generateSpeech(
        context: Context, setting: TTSProviderSetting.MiniMax, text: String,
    ): TTSResponse = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("model", setting.model)
            put("text", text)
            put("stream", false)
            put("output_format", "mp3")
            put("voice_setting", JSONObject().apply {
                put("voice_id", setting.voiceId)
                put("emotion", setting.emotion)
                put("speed", setting.speed)
            })
        }
        val request = Request.Builder()
            .url("${setting.baseUrl.trimEnd('/')}/t2a_v2")
            .addHeader("Authorization", "Bearer ${setting.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw Exception("TTS 请求失败：${response.code} ${response.message}")
        val audio = JSONObject(response.body.string())
            .optJSONObject("data")?.optString("audio")
            ?: throw Exception("MiniMax TTS 未返回音频")
        TTSResponse(Base64.decode(audio, Base64.DEFAULT), AudioFormat.MP3)
    }
}

/** 通义 Qwen TTS（DashScope 多模态生成 SSE），累积 base64 PCM 包成 WAV */
object QwenTTSProvider : TTSProvider<TTSProviderSetting.Qwen> {
    private val client = Http.client.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()

    override suspend fun generateSpeech(
        context: Context, setting: TTSProviderSetting.Qwen, text: String,
    ): TTSResponse = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("model", setting.model)
            put("input", JSONObject().apply {
                put("text", text)
                put("voice", setting.voice)
                put("language_type", setting.languageType)
            })
        }
        val request = Request.Builder()
            .url("${setting.baseUrl.trimEnd('/')}/services/aigc/multimodal-generation/generation")
            .addHeader("Authorization", "Bearer ${setting.apiKey}")
            .addHeader("Content-Type", "application/json")
            .addHeader("X-DashScope-SSE", "enable")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw Exception("TTS 请求失败：${response.code} ${response.message}")
        val pcm = ByteArrayOutputStream()
        response.body.byteStream().bufferedReader().use { reader ->
            var buf = StringBuilder()
            reader.lineSequence().forEach { line ->
                if (line.startsWith("data:")) {
                    buf.append(line.removePrefix("data:"))
                } else if (line.isEmpty() && buf.isNotEmpty()) {
                    val json = runCatching { JSONObject(buf.toString()) }.getOrNull()
                    val audio = json?.optJSONObject("output")?.optJSONObject("audio")?.optString("data")
                    if (!audio.isNullOrEmpty()) pcm.write(Base64.decode(audio, Base64.DEFAULT))
                    buf = StringBuilder()
                }
            }
        }
        if (pcm.size() == 0) throw Exception("Qwen TTS 未返回音频")
        TTSResponse(pcm16ToWav(pcm.toByteArray(), 24000), AudioFormat.WAV)
    }
}

/** 小米 MiMo TTS（OpenAI 兼容 chat/completions SSE），累积 delta 音频 base64 PCM 包成 WAV */
object MiMoTTSProvider : TTSProvider<TTSProviderSetting.MiMo> {
    private val client = Http.client.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()

    override suspend fun generateSpeech(
        context: Context, setting: TTSProviderSetting.MiMo, text: String,
    ): TTSResponse = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("model", setting.model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "assistant")
                    put("content", text)
                })
            })
            put("audio", JSONObject().apply {
                put("format", "pcm16")
                put("voice", setting.voice)
            })
            put("stream", true)
        }
        val request = Request.Builder()
            .url("${setting.baseUrl.trimEnd('/')}/chat/completions")
            .addHeader("api-key", setting.apiKey)
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw Exception("TTS 请求失败：${response.code} ${response.message}")
        val pcm = ByteArrayOutputStream()
        response.body.byteStream().bufferedReader().use { reader ->
            var buf = StringBuilder()
            reader.lineSequence().forEach { line ->
                if (line.startsWith("data:")) {
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]" || payload.isEmpty()) return@forEach
                    val audio = runCatching {
                        JSONObject(payload)
                            .optJSONArray("choices")?.optJSONObject(0)
                            ?.optJSONObject("delta")?.optJSONObject("audio")?.optString("data")
                    }.getOrNull()
                    if (!audio.isNullOrEmpty()) pcm.write(Base64.decode(audio, Base64.DEFAULT))
                }
            }
        }
        if (pcm.size() == 0) throw Exception("MiMo TTS 未返回音频")
        TTSResponse(pcm16ToWav(pcm.toByteArray(), 24000), AudioFormat.WAV)
    }
}
