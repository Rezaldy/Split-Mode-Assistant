@file:Suppress("UnstableApiUsage")

package com.transtrend.ai.assistant.chatApp.viewmodel

import com.transtrend.ai.assistant.ModelsApi
import com.transtrend.ai.assistant.ModelsStateDto
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import fleet.rpc.client.durable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

@Service(Service.Level.APP)
class FrontendModelsModel(coroutineScope: CoroutineScope) {
    companion object {
        fun getInstance(): FrontendModelsModel =
            ApplicationManager.getApplication().getService(FrontendModelsModel::class.java)
    }

    val stateFlow: StateFlow<ModelsStateDto> = flow {
        durable {
            ModelsApi.getInstance().getStateFlow().collect { valueFromBackend ->
                emit(valueFromBackend)
            }
        }
    }.stateIn(coroutineScope, initialValue = ModelsStateDto(), started = SharingStarted.Lazily)

    suspend fun select(name: String) {
        ModelsApi.getInstance().selectModel(name)
    }

    suspend fun refresh() {
        ModelsApi.getInstance().refresh()
    }
}
