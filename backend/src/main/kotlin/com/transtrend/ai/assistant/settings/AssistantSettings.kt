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
}
