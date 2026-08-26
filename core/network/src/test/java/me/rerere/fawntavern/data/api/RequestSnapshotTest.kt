package me.rerere.fawntavern.data.api

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestSnapshotTest {
    @Test
    fun imagePayloadsAndEndpointSecretsAreRedacted() {
        val rawBase64 = "a".repeat(128)
        val body = JSONObject().put("api_key", "body-secret").put("contents", JSONArray().put(
            JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("inline_data", JSONObject()
                    .put("mime_type", "image/png")
                    .put("data", rawBase64)))
                .put(JSONObject().put("image_url", JSONObject()
                    .put("url", "data:image/jpeg;base64,$rawBase64"))))
        ))

        val snapshot = requestSnapshot(
            "https://example.com/generate?key=secret-value&alt=sse",
            body,
        )

        assertEquals(
            "https://example.com/generate?key=[已省略]&alt=sse",
            snapshot.endpoint,
        )
        assertFalse(snapshot.body.contains(rawBase64))
        assertFalse(snapshot.body.contains("body-secret"))
        assertTrue(snapshot.body.contains("[已省略 128 字符]"))
    }

    @Test
    fun ordinaryRequestValuesRemainAvailable() {
        val body = JSONObject()
            .put("model", "gpt-image-2")
            .put("quality", "high")
            .put("size", "1536x1024")

        val parsed = JSONObject(requestSnapshot("https://example.com/images", body).body)

        assertEquals("gpt-image-2", parsed.getString("model"))
        assertEquals("high", parsed.getString("quality"))
        assertEquals("1536x1024", parsed.getString("size"))
    }
}
