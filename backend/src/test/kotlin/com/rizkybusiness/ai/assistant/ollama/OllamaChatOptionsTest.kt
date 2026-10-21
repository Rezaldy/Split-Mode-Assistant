package com.rizkybusiness.ai.assistant.ollama

import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.Assert.assertTrue

class OllamaChatOptionsTest {
    @Test
    fun `chat request encodes temperature`() {
        val body = Json { ignoreUnknownKeys = true }.encodeToString(
            OllamaChatRequest.serializer(),
            OllamaChatRequest("m", emptyList(), options = OllamaChatOptions(numCtx = 1, numPredict = -1, temperature = 0.2f)),
        )
        assertTrue(body, "\"temperature\":0.2" in body)
    }
}
