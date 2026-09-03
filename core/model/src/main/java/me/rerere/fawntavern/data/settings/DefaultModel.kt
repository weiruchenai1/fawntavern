package me.rerere.fawntavern.data.settings

enum class DefaultModelRole(val storageKey: String) {
    CHAT("chat"),
    TITLE("title"),
    SUMMARY("summary"),
    TRANSLATION("translation"),
}

object DefaultModelPrompts {
    const val TITLE =
        "I will give you some dialogue content in the `<content>` block.\n" +
            "You need to summarize the conversation between user and assistant into a short title.\n" +
            "1. The title language should be consistent with the user's primary language\n" +
            "2. Do not use punctuation or other special symbols\n" +
            "3. Reply directly with the title\n" +
            "4. The title should be short (around 5-10 words)\n" +
            "5. Output only the title, no quotes or extra text\n\n" +
            "<content>\n" +
            "{content}\n" +
            "</content>"

    const val SUMMARY =
        "You are a summarization engine for a roleplay chat. Merge the previous summary (if any) and the new " +
            "conversation into a single, updated summary. Preserve key facts, character state, relationships, ongoing " +
            "goals and unresolved threads; drop small talk. Write in the same language as the conversation. Aim for " +
            "about {target} tokens. Output only the summary text, with no preamble."

    const val TRANSLATION =
        "Translate the user's text into {language}. Preserve meaning, names, tone, paragraphs, and formatting. " +
            "Output only the translation without commentary."
}
