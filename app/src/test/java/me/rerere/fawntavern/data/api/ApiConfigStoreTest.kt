package me.rerere.fawntavern.data.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiConfigStoreTest {
    @Test
    fun portableConfigPreservesResponsesApiSetting() {
        val config = ApiConfigStore.parsePortable(
            """{"formatVersion":1,"providers":[{"id":"provider","name":"OpenAI","type":"openai","baseUrl":"https://api.openai.com/v1","enabled":true,"useResponseApi":true,"models":[]}],"currentModel":""}""",
        )

        assertTrue(config.providers.single().useResponseApi)
    }

    @Test
    fun legacyPortableConfigDefaultsResponsesApiOff() {
        val config = ApiConfigStore.parsePortable(
            """{"formatVersion":1,"providers":[{"id":"provider","name":"OpenAI","type":"openai","baseUrl":"https://api.openai.com/v1","enabled":true,"models":[]}],"currentModel":""}""",
        )

        assertFalse(config.providers.single().useResponseApi)
    }
}
