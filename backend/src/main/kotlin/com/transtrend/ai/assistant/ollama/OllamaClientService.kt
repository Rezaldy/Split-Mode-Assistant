package com.transtrend.ai.assistant.ollama

import com.transtrend.ai.assistant.settings.AssistantSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service

/**
 * Hands out the [OllamaClient] for the currently effective base URL (env > settings >
 * default), reusing one client instance until the URL changes.
 */
@Service(Service.Level.APP)
class OllamaClientService {

    companion object {
        fun getInstance(): OllamaClientService =
            ApplicationManager.getApplication().getService(OllamaClientService::class.java)
    }

    @Volatile
    private var cached: OllamaClient? = null

    @Volatile
    private var cachedEmbedding: OllamaClient? = null

    fun client(): OllamaClient {
        val settings = AssistantSettings.getInstance()
        val baseUrl = settings.effectiveBaseUrl
        val useProxy = settings.useProxy
        cached?.takeIf { it.baseUrl == baseUrl && it.useProxy == useProxy }?.let { return it }
        return OllamaClient(baseUrl, useProxy).also { cached = it }
    }

    /** Client for embedding traffic; may target a different Ollama instance than chat. */
    fun embeddingClient(): OllamaClient {
        val settings = AssistantSettings.getInstance()
        val baseUrl = settings.effectiveEmbeddingBaseUrl
        if (baseUrl == settings.effectiveBaseUrl) return client()
        val useProxy = settings.useProxy
        cachedEmbedding?.takeIf { it.baseUrl == baseUrl && it.useProxy == useProxy }?.let { return it }
        return OllamaClient(baseUrl, useProxy).also { cachedEmbedding = it }
    }
}
