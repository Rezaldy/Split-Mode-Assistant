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

    fun client(): OllamaClient {
        val baseUrl = AssistantSettings.getInstance().effectiveBaseUrl
        cached?.takeIf { it.baseUrl == baseUrl }?.let { return it }
        return OllamaClient(baseUrl).also { cached = it }
    }
}
