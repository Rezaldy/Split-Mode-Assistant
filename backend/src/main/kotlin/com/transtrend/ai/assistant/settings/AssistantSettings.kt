package com.transtrend.ai.assistant.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Backend-registered persistent settings. In split mode this state lives on the host
 * ("Settings on Host"), which is correct: the model-source connection is made from there.
 * Env overrides (`OLLAMA_BASE_URL`, `OLLAMA_MODEL`) always win over stored state so
 * containerized backends can be configured from the pod spec.
 */
@State(name = "SplitModeAssistantSettings", storages = [Storage("splitModeAssistant.xml")])
@Service(Service.Level.APP)
class AssistantSettings : PersistentStateComponent<AssistantSettings.State> {

    class State {
        var baseUrl: String = ""
        var selectedModel: String = ""
        var indexingEnabled: Boolean = false
        var embeddingModel: String = ""
        var embeddingBaseUrl: String = ""
        var useProxy: Boolean = false
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        const val DEFAULT_BASE_URL = "http://localhost:11434"

        fun getInstance(): AssistantSettings =
            ApplicationManager.getApplication().getService(AssistantSettings::class.java)
    }

    val effectiveBaseUrl: String
        get() = (System.getenv("OLLAMA_BASE_URL")?.takeIf { it.isNotBlank() }
            ?: state.baseUrl.takeIf { it.isNotBlank() }
            ?: DEFAULT_BASE_URL).trimEnd('/')

    val modelEnvOverride: String?
        get() = System.getenv("OLLAMA_MODEL")?.takeIf { it.isNotBlank() }

    var selectedModel: String?
        get() = state.selectedModel.takeIf { it.isNotBlank() }
        set(value) {
            state.selectedModel = value.orEmpty()
        }

    var storedBaseUrl: String
        get() = state.baseUrl
        set(value) {
            state.baseUrl = value.trim().trimEnd('/')
        }

    var indexingEnabled: Boolean
        get() = state.indexingEnabled
        set(value) {
            state.indexingEnabled = value
        }

    /** Off = direct connection (default); on = honor the IDE's HTTP proxy configuration. */
    var useProxy: Boolean
        get() = state.useProxy
        set(value) {
            state.useProxy = value
        }

    val embeddingModelEnvOverride: String?
        get() = System.getenv("OLLAMA_EMBED_MODEL")?.takeIf { it.isNotBlank() }

    /** Stored embedding model; blank = auto-pick by name heuristic. */
    var storedEmbeddingModel: String
        get() = state.embeddingModel
        set(value) {
            state.embeddingModel = value.trim()
        }

    /** Stored embedding source URL; blank = embeddings go to the main model source. */
    var storedEmbeddingBaseUrl: String
        get() = state.embeddingBaseUrl
        set(value) {
            state.embeddingBaseUrl = value.trim().trimEnd('/')
        }

    val embeddingBaseUrlEnvOverride: String?
        get() = System.getenv("OLLAMA_EMBED_BASE_URL")?.takeIf { it.isNotBlank() }

    /** Env > stored > the main model-source URL. Chat and embeddings may target different instances. */
    val effectiveEmbeddingBaseUrl: String
        get() = (embeddingBaseUrlEnvOverride
            ?: state.embeddingBaseUrl.takeIf { it.isNotBlank() }
            ?: effectiveBaseUrl).trimEnd('/')
}
