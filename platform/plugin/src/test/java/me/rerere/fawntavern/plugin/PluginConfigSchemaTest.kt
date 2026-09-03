package me.rerere.fawntavern.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginConfigSchemaTest {
    @Test
    fun parsesSupportedNativeControlTypes() {
        val fields = PluginConfigSchema.parse(
            """
            {
              "properties": {
                "enabled": { "type": "boolean", "title": "Enabled", "default": true },
                "depth": { "type": "integer", "minimum": 1, "maximum": 10, "default": 20 },
                "tone": { "type": "string", "enum": ["neutral", "warm"], "default": "warm" },
                "ignored": { "type": "array" }
              }
            }
            """.trimIndent()
        )

        val byKey = fields.associateBy(PluginConfigField::key)
        assertEquals(3, byKey.size)
        assertEquals(true, (byKey.getValue("enabled") as PluginConfigField.BooleanField).default)
        assertEquals(10, (byKey.getValue("depth") as PluginConfigField.IntegerField).default)
        assertEquals(
            listOf("neutral", "warm"),
            (byKey.getValue("tone") as PluginConfigField.StringField).options,
        )
    }

    @Test
    fun rejectsInvalidIntegerRange() {
        val fields = PluginConfigSchema.parse(
            """{"properties":{"depth":{"type":"integer","minimum":10,"maximum":1}}}"""
        )

        assertTrue(fields.isEmpty())
    }
}
