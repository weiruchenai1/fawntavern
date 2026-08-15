package me.rerere.fawntavern.data.diagnostics

internal enum class DiagnosticsRegion {
    MAINLAND_CHINA,
    OTHER,
    UNKNOWN,
}

internal object DiagnosticsRegionDetector {
    private val mainlandChinaTimeZones = setOf(
        "Asia/Shanghai",
        "Asia/Urumqi",
        "Asia/Chongqing",
        "Asia/Harbin",
        "PRC",
        "CTT",
    )

    fun detect(
        localeCountry: String,
        timeZoneId: String,
        ipCountry: String?,
    ): DiagnosticsRegion {
        var score = 0

        normalizedCountry(localeCountry)?.let { country ->
            score += if (country == "CN") 2 else -2
        }
        if (timeZoneId.isNotBlank()) {
            score += if (timeZoneId in mainlandChinaTimeZones) 2 else -2
        }
        normalizedCountry(ipCountry)?.let { country ->
            score += if (country == "CN") 3 else -3
        }

        return when {
            score > 0 -> DiagnosticsRegion.MAINLAND_CHINA
            score < 0 -> DiagnosticsRegion.OTHER
            else -> DiagnosticsRegion.UNKNOWN
        }
    }

    fun parseCloudflareCountry(body: String): String? = body
        .lineSequence()
        .firstOrNull { it.startsWith("loc=") }
        ?.substringAfter('=')
        ?.let(::normalizedCountry)

    private fun normalizedCountry(value: String?): String? = value
        ?.trim()
        ?.uppercase()
        ?.takeIf { it.length == 2 && it.all(Char::isLetter) && it != "XX" }
}
