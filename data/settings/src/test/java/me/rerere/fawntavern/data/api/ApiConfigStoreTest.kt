package me.rerere.fawntavern.data.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiConfigStoreTest {
    @Test
    fun portableConfigPreservesCustomApiPath() {
        val config = ApiConfigStore.parsePortable(
            """{"formatVersion":1,"providers":[{"id":"provider","name":"Gateway","type":"openai","baseUrl":"https://example.com/v1","apiPath":"/custom/chat","enabled":true,"models":[]}],"currentModel":""}""",
        )

        assertEquals("/custom/chat", config.providers.single().apiPath)
        assertEquals("/custom/chat", config.providers.single().chatApiPath)
        assertEquals("", config.providers.single().responsesApiPath)
    }

    @Test
    fun portableConfigPreservesResponsesApiSetting() {
        val config = ApiConfigStore.parsePortable(
            """{"formatVersion":1,"providers":[{"id":"provider","name":"OpenAI","type":"openai","baseUrl":"https://api.openai.com/v1","apiPath":"/custom/responses","enabled":true,"useResponseApi":true,"models":[]}],"currentModel":""}""",
        )

        assertTrue(config.providers.single().useResponseApi)
        assertEquals("/custom/responses", config.providers.single().responsesApiPath)
        assertEquals("", config.providers.single().chatApiPath)
    }

    @Test
    fun legacyPortableConfigDefaultsResponsesApiOff() {
        val config = ApiConfigStore.parsePortable(
            """{"formatVersion":1,"providers":[{"id":"provider","name":"OpenAI","type":"openai","baseUrl":"https://api.openai.com/v1","enabled":true,"models":[]}],"currentModel":""}""",
        )

        assertFalse(config.providers.single().useResponseApi)
    }

    @Test
    fun legacyImageModelInfersImageTask() {
        val config = ApiConfigStore.parsePortable(
            """{"formatVersion":1,"providers":[{"id":"provider","name":"Images","type":"openai","baseUrl":"https://example.com/v1","models":[{"id":"image-model","input":["TEXT"],"output":["IMAGE"]}]}],"currentModel":""}""",
        )

        assertEquals(ModelType.IMAGE, config.providers.single().models.single().type)
        assertEquals(
            ImageGenerationRoute.DIRECT,
            config.providers.single().models.single().imageGenerationRoute,
        )
    }

    @Test
    fun portableConfigReadsExplicitModelTask() {
        val config = ApiConfigStore.parsePortable(
            """{"formatVersion":2,"providers":[{"id":"provider","name":"Images","type":"openai","baseUrl":"https://example.com","models":[{"id":"gpt-5.6","input":["TEXT"],"output":["IMAGE"],"modelType":"IMAGE","imageGenerationRoute":"RESPONSES_TOOL"}]}],"currentModel":""}""",
        )

        assertEquals(ModelType.IMAGE, config.providers.single().models.single().type)
        assertEquals(
            ImageGenerationRoute.RESPONSES_TOOL,
            config.providers.single().models.single().imageGenerationRoute,
        )
    }

    @Test
    fun portableConfigPreservesSplitProviderPathsAndModelChatRoute() {
        val config = ApiConfigStore.parsePortable(
            """{"formatVersion":3,"providers":[{"id":"provider","name":"Mixed","type":"openai","baseUrl":"https://example.com/v1","chatApiPath":"/chat","responsesApiPath":"/responses-v2","imageGenerationApiPath":"/image/generate","imageEditApiPath":"/image/edit","models":[{"id":"chat-model","modelType":"CHAT","chatGenerationRoute":"RESPONSES"}]}],"currentModel":""}""",
        )

        val provider = config.providers.single()
        assertEquals("/chat", provider.chatApiPath)
        assertEquals("/responses-v2", provider.responsesApiPath)
        assertEquals("/image/generate", provider.imageGenerationApiPath)
        assertEquals("/image/edit", provider.imageEditApiPath)
        assertEquals(
            ChatGenerationRoute.RESPONSES,
            provider.models.single().chatGenerationRoute,
        )
    }
}
