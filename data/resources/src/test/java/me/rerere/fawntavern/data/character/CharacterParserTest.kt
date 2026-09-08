package me.rerere.fawntavern.data.character

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CharacterParserTest {
    @Test
    fun missingTalkativenessDefaultsEvenWhenExtensionsExist() {
        val fixtures = listOf(
            """{}""",
            """{"extensions": {}}""",
            """{"data": {"extensions": {}}}""",
            """{"data": {"extensions": {"depth_prompt": {"prompt": "Note"}}}}""",
        )

        fixtures.forEach { fixture ->
            assertEquals(fixture, 0.5f, CharacterParser.parse(JSONObject(fixture)).talkativeness, 0f)
        }
    }

    @Test
    fun missingExtensionTalkativenessFallsBackToLegacyRootValue() {
        val json = JSONObject()
            .put("talkativeness", 0.25)
            .put("data", JSONObject().put("extensions", JSONObject()))

        assertEquals(0.25f, CharacterParser.parse(json).talkativeness, 0f)
    }

    @Test
    fun validExtensionTalkativenessTakesPrecedenceIncludingZeroAndNumericStrings() {
        listOf(0.0 to 0f, 0.75 to 0.75f, "0.625" to 0.625f).forEach { (value, expected) ->
            val json = JSONObject()
                .put("talkativeness", 0.25)
                .put("data", JSONObject().put("extensions", JSONObject().put("talkativeness", value)))

            assertEquals(value.toString(), expected, CharacterParser.parse(json).talkativeness, 0f)
        }
    }

    @Test
    fun invalidExtensionTalkativenessFallsBackToLegacyRootValue() {
        val invalidValues = listOf(JSONObject.NULL, "invalid", "NaN", "Infinity", "-Infinity", 1e100, -1e100)
        invalidValues.forEach { value ->
            val json = JSONObject()
                .put("talkativeness", 0.25)
                .put("data", JSONObject().put("extensions", JSONObject().put("talkativeness", value)))

            assertEquals(value.toString(), 0.25f, CharacterParser.parse(json).talkativeness, 0f)
        }
    }

    @Test
    fun invalidTalkativenessInBothLocationsFallsBackToDefault() {
        val invalidValues = listOf(JSONObject.NULL, "invalid", "NaN", "Infinity", "-Infinity", 1e100, -1e100)
        invalidValues.forEach { value ->
            val json = JSONObject()
                .put("talkativeness", value)
                .put("data", JSONObject().put("extensions", JSONObject().put("talkativeness", value)))

            assertEquals(value.toString(), 0.5f, CharacterParser.parse(json).talkativeness, 0f)
        }
    }
}
