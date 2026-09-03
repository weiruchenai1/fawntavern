package me.rerere.fawntavern.data.backup

enum class BackupSection {
    CHARACTERS,
    PRESETS,
    WORLDBOOKS,
    REGEXSETS,
    CHATS,
    API_CONFIG,
    SEARCH_CONFIG,
    TTS_CONFIG,
    AVATAR,
}

object BackupDefaults {
    val exportSections: Set<BackupSection> = setOf(
        BackupSection.CHARACTERS,
        BackupSection.PRESETS,
        BackupSection.WORLDBOOKS,
        BackupSection.REGEXSETS,
        BackupSection.CHATS,
        BackupSection.AVATAR,
    )
}

data class BackupImportResult(val files: Int, val sessions: Int)
