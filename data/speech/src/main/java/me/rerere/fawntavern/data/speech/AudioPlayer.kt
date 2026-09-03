package me.rerere.fawntavern.data.speech

import android.media.MediaPlayer
import android.media.PlaybackParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 单段音频的实时播放状态（供悬浮工具栏展示进度） */
internal data class AudioPlayerState(
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)

/**
 * 音频播放器：封装 MediaPlayer 播放本地音频文件，一次只播一段。
 * prepareAsync 异步准备不阻塞调用线程；暴露实时进度，支持暂停/继续/变速。
 */
internal class AudioPlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: MediaPlayer? = null
    private var poller: Job? = null
    private var onEnd: (() -> Unit)? = null

    private val _state = MutableStateFlow(AudioPlayerState())
    val state: StateFlow<AudioPlayerState> = _state.asStateFlow()

    /** 播放 [path] 指向的音频；播放结束或出错时回调 [onEnd]（主线程）。先停掉上一段。 */
    fun play(path: String, onEnd: () -> Unit) {
        stop()
        this.onEnd = onEnd
        _state.value = AudioPlayerState()
        val mp = MediaPlayer().apply {
            setOnPreparedListener {
                _state.update { s -> s.copy(playing = true, durationMs = this.duration.toLong().coerceAtLeast(0)) }
                start()
                startPoller()
            }
            setOnCompletionListener { finish() }
            setOnErrorListener { _, _, _ -> finish(); true }
        }
        try {
            mp.setDataSource(path)
            mp.prepareAsync()
            player = mp
        } catch (e: Exception) {
            mp.release()
            finish()
        }
    }

    fun pause() {
        player?.pause()
        _state.update { it.copy(playing = false) }
    }

    fun resume() {
        player?.start()
        _state.update { it.copy(playing = true) }
    }

    fun setSpeed(speed: Float) {
        val p = player ?: return
        try { p.playbackParams = PlaybackParams().setSpeed(speed) } catch (_: Exception) {}
    }

    /** 相对当前进度快进 [ms] 毫秒 */
    fun seekBy(ms: Long) {
        val p = player ?: return
        try { p.seekTo(((p.currentPosition) + ms).toInt().coerceAtLeast(0)) } catch (_: Exception) {}
    }

    /** 立即停止播放（不触发 onEnd） */
    fun stop() {
        stopPoller()
        stopPlayerOnly()
        onEnd = null
        _state.value = AudioPlayerState()
    }

    private fun finish() {
        stopPoller()
        stopPlayerOnly()
        onEnd?.invoke()
        onEnd = null
        _state.value = AudioPlayerState()
    }

    private fun stopPlayerOnly() {
        val p = player
        player = null
        if (p != null) {
            p.setOnPreparedListener(null)
            p.setOnCompletionListener(null)
            p.setOnErrorListener(null)
            try { p.stop() } catch (_: Exception) {}
            p.release()
        }
    }

    private fun startPoller() {
        stopPoller()
        poller = scope.launch {
            while (isActive) {
                val p = player
                if (p != null) {
                    _state.update {
                        it.copy(
                            positionMs = p.currentPosition.toLong().coerceAtLeast(0),
                            playing = p.isPlaying,
                        )
                    }
                }
                delay(200)
            }
        }
    }

    private fun stopPoller() {
        poller?.cancel()
        poller = null
    }
}
