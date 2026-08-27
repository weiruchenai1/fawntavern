package me.rerere.fawntavern.data.preset

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class PresetParserTest {
    @Test
    fun parsesSillyTavernEmbeddedRegexScripts() {
        val preset = PresetParser.parse(
            JSONObject(
                """
                {
                  "extensions": {
                    "regex_scripts": [{
                      "id": "nested",
                      "scriptName": "Remove thoughts",
                      "findRegex": "/<think>[\\s\\S]*?<\\/think>/gi",
                      "replaceString": "",
                      "placement": [2],
                      "markdownOnly": true,
                      "promptOnly": false
                    }]
                  }
                }
                """.trimIndent()
            )
        )

        assertEquals(1, preset.regexScripts.size)
        assertEquals("nested", preset.regexScripts.single().id)
        assertEquals("Remove thoughts", preset.regexScripts.single().scriptName)
    }

    @Test
    fun fallsBackToLegacyRootRegexScripts() {
        val preset = PresetParser.parse(
            JSONObject(
                """
                {
                  "regex_scripts": [{
                    "id": "legacy",
                    "scriptName": "Legacy",
                    "findRegex": "/old/g",
                    "replaceString": "new"
                  }]
                }
                """.trimIndent()
            )
        )

        assertEquals("legacy", preset.regexScripts.single().id)
    }

    @Test
    fun standardExtensionFieldWinsWhenBothLayoutsExist() {
        val preset = PresetParser.parse(
            JSONObject(
                """
                {
                  "extensions": {"regex_scripts": [{"id": "standard"}]},
                  "regex_scripts": [{"id": "legacy"}]
                }
                """.trimIndent()
            )
        )

        assertEquals(listOf("standard"), preset.regexScripts.map { it.id })
    }
}
