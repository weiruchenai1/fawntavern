package me.rerere.fawntavern.data.api

/**
 * 按模型 ID 猜测模态与能力。
 *
 * 把 ID 切成字母/数字 token，规则要求自己的 token 按序出现（子序列匹配，允许中间隔别的 token），
 * 命中 token 最多的规则胜出、同分合并 —— 于是 "claude-3-7-sonnet" 取更具体的 `claude 3 7`
 * 而不是 `claude 3`，"anthropic/claude-3.7-sonnet" 这类带前缀的 ID 也照样命中。
 * 规则表覆盖不到的新命名靠一组通用修饰词（vl / vision / thinking…）兜底叠加。
 *
 * 结果只是添加模型时的预填值，用户可在模型详情里改，故宁可宽松也不做严格版本区分。
 */
object ModelRegistry {

    data class Capabilities(
        val input: List<Modality>,
        val output: List<Modality>,
        val abilities: List<ModelAbility>,
    )

    fun infer(modelId: String): Capabilities {
        val tokens = tokenize(modelId)
        var best = 0
        var vision = false
        var imageOut = false
        var tool = false
        var reasoning = false
        RULES.forEach { rule ->
            val score = rule.score(tokens) ?: return@forEach
            if (score < best) return@forEach
            if (score > best) {
                best = score
                vision = false; imageOut = false; tool = false; reasoning = false
            }
            vision = vision || rule.vision
            imageOut = imageOut || rule.imageOut
            tool = tool || rule.tool
            reasoning = reasoning || rule.reasoning
        }
        vision = vision || tokens.any { it in VISION_WORDS }
        imageOut = imageOut || tokens.any { it in IMAGE_OUT_WORDS }
        reasoning = reasoning || tokens.any { it in REASONING_WORDS }
        return Capabilities(
            input = modalities(image = vision),
            output = modalities(image = imageOut),
            abilities = buildList {
                if (tool) add(ModelAbility.TOOL)
                if (reasoning) add(ModelAbility.REASONING)
            },
        )
    }

    private fun modalities(image: Boolean) =
        if (image) listOf(Modality.TEXT, Modality.IMAGE) else listOf(Modality.TEXT)

    // 通用修饰词：规则表没收录的型号（尤其是各家不断新出的变体）靠它们兜底
    private val VISION_WORDS = setOf("vl", "vision", "omni", "multimodal")
    private val IMAGE_OUT_WORDS = setOf("image", "imagen", "banana", "diffusion")
    private val REASONING_WORDS = setOf("thinking", "reasoner", "reasoning")

    private class Rule(
        spec: String,
        val vision: Boolean = false,
        val imageOut: Boolean = false,
        val tool: Boolean = false,
        val reasoning: Boolean = false,
    ) {
        // 每段可写多个候选（"sonnet|opus"），命中其一即可
        private val specs: List<Set<String>> =
            spec.split(' ').filter { it.isNotBlank() }.map { it.split('|').toSet() }

        /** 子序列匹配，返回命中的 token 数（越多越具体）；不匹配返回 null */
        fun score(tokens: List<String>): Int? {
            if (specs.isEmpty()) return null
            var i = 0
            for (token in tokens) {
                if (token in specs[i]) {
                    i++
                    if (i == specs.size) return specs.size
                }
            }
            return null
        }
    }

    private val RULES = listOf(
        // OpenAI
        Rule("gpt|chatgpt 3 5", tool = true),
        Rule("gpt|chatgpt 4", tool = true),
        Rule("gpt|chatgpt 4 o", vision = true, tool = true),
        Rule("gpt|chatgpt 4 turbo", vision = true, tool = true),
        Rule("gpt 4 1", vision = true, tool = true),
        Rule("gpt 4 5", vision = true, tool = true),
        Rule("gpt 5", vision = true, tool = true, reasoning = true),
        Rule("gpt oss", tool = true, reasoning = true),
        Rule("o 1|3|4", vision = true, tool = true, reasoning = true),
        Rule("codex", tool = true, reasoning = true),
        Rule("gpt image", vision = true, imageOut = true),
        Rule("dall e", imageOut = true),
        // Google
        Rule("gemini", vision = true, tool = true),
        Rule("gemini 2 5", vision = true, tool = true, reasoning = true),
        Rule("gemini 3", vision = true, tool = true, reasoning = true),
        Rule("gemma", tool = true),
        Rule("gemma 3", vision = true, tool = true),
        Rule("imagen", imageOut = true),
        // Anthropic
        Rule("claude", vision = true, tool = true),
        Rule("claude 3 7", vision = true, tool = true, reasoning = true),
        Rule("claude 4", vision = true, tool = true, reasoning = true),
        Rule("claude 5", vision = true, tool = true, reasoning = true),
        Rule("claude sonnet|opus|haiku 4", vision = true, tool = true, reasoning = true),
        Rule("claude sonnet|opus|haiku 5", vision = true, tool = true, reasoning = true),
        // DeepSeek
        Rule("deepseek", tool = true),
        Rule("deepseek v 3 1", tool = true, reasoning = true),
        Rule("deepseek v 3 2", tool = true, reasoning = true),
        Rule("deepseek v 4", tool = true, reasoning = true),
        Rule("deepseek r 1", tool = true, reasoning = true),
        // 阿里
        Rule("qwen", tool = true),
        Rule("qwen 3", tool = true, reasoning = true),
        Rule("qwq", tool = true, reasoning = true),
        Rule("qvq", vision = true, tool = true, reasoning = true),
        // 智谱
        Rule("glm", tool = true),
        Rule("glm 4 5", tool = true, reasoning = true),
        Rule("glm 4 6", tool = true, reasoning = true),
        Rule("glm 4 7", tool = true, reasoning = true),
        Rule("glm 5", tool = true, reasoning = true),
        Rule("glm 4 v", vision = true, tool = true),
        // 月之暗面
        Rule("moonshot", tool = true),
        Rule("kimi", tool = true),
        Rule("kimi k 2", tool = true, reasoning = true),
        // 字节
        Rule("doubao", tool = true),
        Rule("doubao 1 6", vision = true, tool = true, reasoning = true),
        Rule("doubao 1 8", vision = true, tool = true, reasoning = true),
        // xAI
        Rule("grok", tool = true),
        Rule("grok 3", tool = true, reasoning = true),
        Rule("grok 4", vision = true, tool = true, reasoning = true),
        Rule("grok imagine image", vision = true, imageOut = true),
        // MiniMax / 阶跃 / 小米 / 零一
        Rule("minimax", tool = true),
        Rule("minimax m 1|2|3", tool = true, reasoning = true),
        Rule("step 1 v", vision = true, tool = true),
        Rule("step 3", vision = true, tool = true, reasoning = true),
        Rule("mimo", tool = true, reasoning = true),
        Rule("yi", tool = true),
        // Meta / Mistral
        Rule("llama", tool = true),
        Rule("llama 3 2", vision = true, tool = true),
        Rule("llama 4", vision = true, tool = true),
        Rule("mistral", tool = true),
        Rule("magistral", tool = true, reasoning = true),
        Rule("pixtral", vision = true, tool = true),
    )

    /** 切成小写的字母串 / 数字串，分隔符直接丢弃 */
    private fun tokenize(modelId: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var digits = false
        modelId.lowercase().forEach { ch ->
            when {
                ch.isLetter() || ch.isDigit() -> {
                    if (sb.isNotEmpty() && digits != ch.isDigit()) {
                        out.add(sb.toString()); sb.clear()
                    }
                    digits = ch.isDigit()
                    sb.append(ch)
                }
                sb.isNotEmpty() -> { out.add(sb.toString()); sb.clear() }
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }
}
