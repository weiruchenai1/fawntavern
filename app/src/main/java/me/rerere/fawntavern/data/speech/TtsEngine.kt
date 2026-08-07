package me.rerere.fawntavern.data.speech

import android.content.Context
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** TTS 朗读的整体状态（供悬浮工具栏展示与控制） */
data class TtsUiState(
    val speaking: Boolean = false,
    val paused: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1.0f,
    val chunkIndex: Int = 0,   // 0-based 当前分片
    val totalChunks: Int = 0,
)

/**
 * TTS 朗读编排器：
 * 长文本分片 → 逐片合成 → 顺序播放。支持暂停/继续/快进/变速，播放状态实时外露。
 */
class TtsEngine(
    private val context: Context,
    private val settingProvider: () -> TTSProviderSetting?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val player = AudioPlayer()
    private var worker: Job? = null
    private var currentSpeed = 1.0f  // 变速跨多次朗读保持

    private val _ui = MutableStateFlow(TtsUiState())
    val ui: StateFlow<TtsUiState> = _ui.asStateFlow()

    init {
        // 把播放器的实时进度同步到对外状态
        scope.launch {
            player.state.collect { ps ->
                _ui.update { it.copy(positionMs = ps.positionMs, durationMs = ps.durationMs) }
            }
        }
    }

    /**
     * 朗读 [text]。中途被 [stop] 打断或合成/播放失败时也会回调 [onFinished]，
     * 调用方据此复位消息上的朗读按钮状态。
     */
    fun speak(text: String, onFinished: () -> Unit) {
        stop()
        val setting = settingProvider() ?: run {
            onFinished()  // 未配置 provider 也要回调，调用方据此复位按钮状态
            return
        }
        val chunks = TextChunker().split(text)
        if (chunks.isEmpty()) {
            onFinished()
            return
        }
        worker = scope.launch {
            _ui.value = TtsUiState(speaking = true, totalChunks = chunks.size, speed = currentSpeed)
            try {
                for ((i, chunk) in chunks.withIndex()) {
                    if (!isActive) break
                    _ui.update { it.copy(chunkIndex = i) }
                    val audio = withContext(Dispatchers.IO) {
                        createTtsProvider(setting).generateSpeech(context, setting, chunk)
                    }
                    if (!isActive) break
                    val file = File(context.cacheDir, "tts_${System.currentTimeMillis()}_$i.${if (audio.format == AudioFormat.WAV) "wav" else "mp3"}")
                    withContext(Dispatchers.IO) { file.writeBytes(audio.data) }
                    playFileAndWait(file)
                    file.delete()
                }
            } catch (_: CancellationException) {
            } catch (_: Exception) {
                // 单条合成/播放失败：放弃剩余分片，静默结束
            } finally {
                player.stop()
                _ui.value = TtsUiState()
                onFinished()
            }
        }
    }

    /** 播放一段文件并等待播放完成；协程取消时同步停止 */
    private suspend fun playFileAndWait(file: File) = suspendCancellableCoroutine<Unit> { cont ->
        player.play(file.absolutePath) {
            if (cont.isActive) cont.resume(Unit)
        }
        cont.invokeOnCancellation { player.stop() }
    }

    fun pause() {
        if (!_ui.value.speaking) return
        player.pause()
        _ui.update { it.copy(paused = true) }
    }

    fun resume() {
        if (!_ui.value.speaking) return
        player.resume()
        _ui.update { it.copy(paused = false) }
    }

    fun setSpeed(speed: Float) {
        currentSpeed = speed
        player.setSpeed(speed)
        _ui.update { it.copy(speed = speed) }
    }

    /** 当前段快进 [ms] 毫秒 */
    fun fastForward(ms: Long = 5_000) {
        player.seekBy(ms)
    }

    /** 停止朗读（调用方自行复位自身状态） */
    fun stop() {
        worker?.cancel()
        worker = null
        player.stop()
        _ui.value = TtsUiState()
    }

    fun release() {
        stop()
        scope.cancel()
    }
}
