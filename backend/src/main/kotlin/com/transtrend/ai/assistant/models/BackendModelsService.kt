package com.transtrend.ai.assistant.models

import com.transtrend.ai.assistant.ModelsApi
import com.transtrend.ai.assistant.ModelsStateDto
import com.transtrend.ai.assistant.ModularPluginBackendBundle
import com.transtrend.ai.assistant.ollama.OllamaClientService
import com.transtrend.ai.assistant.ollama.OllamaException
import com.transtrend.ai.assistant.settings.AssistantSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns model discovery + selection state. Selection precedence for chat requests:
 * `OLLAMA_MODEL` env > persisted selection > first model from `/api/tags`.
 */
@Service(Service.Level.APP)
class BackendModelsService(private val scope: CoroutineScope) {

    companion object {
        fun getInstance(): BackendModelsService =
            ApplicationManager.getApplication().getService(BackendModelsService::class.java)

        private val EMBED_NAME_HINTS = listOf("embed", "bge", "minilm", "arctic")
    }

    private val _state = MutableStateFlow(ModelsStateDto())
    val state: StateFlow<ModelsStateDto> = _state.asStateFlow()

    private val initialRefreshRequested = AtomicBoolean(false)

    /** The state flow, kicking off the first discovery lazily on first use. */
    fun stateFlowWithInitialRefresh(): StateFlow<ModelsStateDto> {
        if (initialRefreshRequested.compareAndSet(false, true)) {
            scope.launch { refresh() }
        }
        return state
    }

    fun refreshAsync() {
        scope.launch { refresh() }
    }

    suspend fun refresh() {
        val settings = AssistantSettings.getInstance()
        val client = OllamaClientService.getInstance().client()
        val envOverride = settings.modelEnvOverride
        try {
            val models = client.listModels()
            val selected = envOverride
                ?: settings.selectedModel?.takeIf { it in models }
                ?: models.firstOrNull()
            _state.value = ModelsStateDto(
                models = models,
                selectedModel = selected,
                envOverride = envOverride != null,
                error = if (models.isEmpty()) {
                    ModularPluginBackendBundle.message("error.no.models", client.baseUrl)
                } else null,
            )
        } catch (e: OllamaException) {
            thisLogger().info("Model discovery failed: ${e.message}")
            _state.value = ModelsStateDto(envOverride = envOverride != null, error = e.message)
        }
    }

    fun select(name: String) {
        AssistantSettings.getInstance().selectedModel = name
        scope.launch { refresh() }
    }

    /** Model used for the next chat request. Never returns a blank name. */
    suspend fun resolveChatModel(): String {
        val settings = AssistantSettings.getInstance()
        settings.modelEnvOverride?.let { return it }
        settings.selectedModel?.let { return it }
        val client = OllamaClientService.getInstance().client()
        return client.listModels().firstOrNull()
            ?: throw OllamaException(ModularPluginBackendBundle.message("error.no.models", client.baseUrl))
    }

    /**
     * Embedding model for indexing: `OLLAMA_EMBED_MODEL` env > stored setting > first
     * model whose name looks like an embedding model (`/api/tags` has no capability field).
     */
    suspend fun resolveEmbeddingModel(): String {
        val settings = AssistantSettings.getInstance()
        settings.embeddingModelEnvOverride?.let { return it }
        settings.storedEmbeddingModel.takeIf { it.isNotBlank() }?.let { return it }
        val client = OllamaClientService.getInstance().client()
        return client.listModels().firstOrNull { name ->
            EMBED_NAME_HINTS.any { hint -> hint in name.lowercase() }
        } ?: throw OllamaException(ModularPluginBackendBundle.message("error.no.embed.model", client.baseUrl))
    }

    /** Embedding-capable candidates from the already-discovered list (no network call). */
    fun embeddingCandidatesFromCache(): List<String> =
        state.value.models.filter { name -> EMBED_NAME_HINTS.any { it in name.lowercase() } }
}

class BackendModelsApi : ModelsApi {
    override suspend fun getStateFlow(): Flow<ModelsStateDto> =
        BackendModelsService.getInstance().stateFlowWithInitialRefresh()

    override suspend fun selectModel(name: String) {
        BackendModelsService.getInstance().select(name)
    }

    override suspend fun refresh() {
        BackendModelsService.getInstance().refresh()
    }
}
