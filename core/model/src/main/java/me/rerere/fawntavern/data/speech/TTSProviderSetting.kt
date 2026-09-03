package me.rerere.fawntavern.data.speech

/**
 * TTS 服务配置。
 * 无 @Serializable —— 持久化走 TtsStore 的 org.json 手写序列化。
 * [key] 是类型标识，[id] 是实例唯一标识（同一类型可配置多个），[name] 为用户可编辑的显示名。
 */
sealed class TTSProviderSetting {
    abstract val key: String
    abstract val id: String
    abstract val name: String

    open val displayName: String get() = name

    fun withName(name: String): TTSProviderSetting = when (this) {
        is SystemTTS -> copy(name = name)
        is OpenAI -> copy(name = name)
        is Groq -> copy(name = name)
        is XAI -> copy(name = name)
        is Gemini -> copy(name = name)
        is MiniMax -> copy(name = name)
        is Qwen -> copy(name = name)
        is MiMo -> copy(name = name)
    }

    /** Android 系统 TTS：离线、无需 API key，开箱即用 */
    data class SystemTTS(
        override val id: String = newTtsId(),
        override var name: String = "系统 TTS",
        var speechRate: Float = 1.0f,
        var pitch: Float = 1.0f,
    ) : TTSProviderSetting() {
        override val key = "system"
    }

    /** OpenAI 语音 API（OpenAI 兼容端点均可） */
    data class OpenAI(
        override val id: String = newTtsId(),
        override var name: String = "OpenAI TTS",
        var apiKey: String = "",
        var baseUrl: String = "https://api.openai.com/v1",
        var model: String = "gpt-4o-mini-tts",
        var voice: String = "alloy",
    ) : TTSProviderSetting() {
        override val key = "openai"
    }

    /** Groq（Orpheus），OpenAI 兼容 /audio/speech，输出 WAV */
    data class Groq(
        override val id: String = newTtsId(),
        override var name: String = "Groq TTS",
        var apiKey: String = "",
        var baseUrl: String = "https://api.groq.com/openai/v1",
        var model: String = "canopylabs/orpheus-v1-english",
        var voice: String = "austin",
    ) : TTSProviderSetting() {
        override val key = "groq"
    }

    /** xAI 语音 API，/tts 端点，输出 MP3 */
    data class XAI(
        override val id: String = newTtsId(),
        override var name: String = "xAI TTS",
        var apiKey: String = "",
        var baseUrl: String = "https://api.x.ai/v1",
        var voiceId: String = "eve",
        var language: String = "auto",
    ) : TTSProviderSetting() {
        override val key = "xai"
    }

    /** Gemini TTS（generativelanguage API），返回 24kHz PCM */
    data class Gemini(
        override val id: String = newTtsId(),
        override var name: String = "Gemini TTS",
        var apiKey: String = "",
        var baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
        var model: String = "gemini-2.5-flash-preview-tts",
        var voiceName: String = "Kore",
    ) : TTSProviderSetting() {
        override val key = "gemini"
    }

    /** MiniMax TTS，t2a_v2 端点，输出 MP3 */
    data class MiniMax(
        override val id: String = newTtsId(),
        override var name: String = "MiniMax TTS",
        var apiKey: String = "",
        var baseUrl: String = "https://api.minimaxi.com/v1",
        var model: String = "speech-2.6-turbo",
        var voiceId: String = "female-shaonv",
        var emotion: String = "calm",
        var speed: Float = 1.0f,
    ) : TTSProviderSetting() {
        override val key = "minimax"
    }

    /** 通义 Qwen TTS（DashScope 多模态生成），返回 24kHz PCM */
    data class Qwen(
        override val id: String = newTtsId(),
        override var name: String = "Qwen TTS",
        var apiKey: String = "",
        var baseUrl: String = "https://dashscope.aliyuncs.com/api/v1",
        var model: String = "qwen3-tts-flash",
        var voice: String = "Cherry",
        var languageType: String = "Auto",
    ) : TTSProviderSetting() {
        override val key = "qwen"
    }

    /** 小米 MiMo TTS（OpenAI 兼容 chat/completions 流式），返回 24kHz PCM */
    data class MiMo(
        override val id: String = newTtsId(),
        override var name: String = "MiMo TTS",
        var apiKey: String = "",
        var baseUrl: String = "https://api.xiaomimimo.com/v1",
        var model: String = "mimo-v2-tts",
        var voice: String = "mimo_default",
    ) : TTSProviderSetting() {
        override val key = "mimo"
    }

    companion object {
        /** 全部可选 provider 类型，配置页「添加」底部面板的预设列表 */
        val ALL: List<TTSProviderSetting> = listOf(
            SystemTTS(), OpenAI(), Groq(), XAI(), Gemini(), MiniMax(), Qwen(), MiMo(),
        )

        /** 按类型 key 建全新实例（每次调用生成新 id），供「添加提供商」使用 */
        fun fromKey(key: String, id: String = newTtsId()): TTSProviderSetting = when (key) {
            "openai" -> OpenAI(id = id)
            "groq" -> Groq(id = id)
            "xai" -> XAI(id = id)
            "gemini" -> Gemini(id = id)
            "minimax" -> MiniMax(id = id)
            "qwen" -> Qwen(id = id)
            "mimo" -> MiMo(id = id)
            else -> SystemTTS(id = id)
        }
    }
}

/** 生成新 TTS 提供商实例 id（UUID 文本） */
fun newTtsId(): String = java.util.UUID.randomUUID().toString()
