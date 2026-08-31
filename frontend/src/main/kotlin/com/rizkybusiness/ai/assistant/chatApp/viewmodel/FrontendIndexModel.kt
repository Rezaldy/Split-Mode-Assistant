@file:Suppress("UnstableApiUsage")

package com.rizkybusiness.ai.assistant.chatApp.viewmodel

import com.rizkybusiness.ai.assistant.IndexApi
import com.rizkybusiness.ai.assistant.IndexStatusDto
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.platform.project.projectId
import fleet.rpc.client.durable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

@Service(Service.Level.PROJECT)
class FrontendIndexModel(
    private val project: Project,
    coroutineScope: CoroutineScope,
) {
    companion object {
        fun getInstance(project: Project): FrontendIndexModel =
            project.getService(FrontendIndexModel::class.java)
    }

    val statusFlow: StateFlow<IndexStatusDto> = flow {
        durable {
            IndexApi.getInstance().getStatusFlow(project.projectId()).collect { valueFromBackend ->
                emit(valueFromBackend)
            }
        }
    }.stateIn(coroutineScope, initialValue = IndexStatusDto(), started = SharingStarted.Lazily)

    suspend fun rebuild() {
        IndexApi.getInstance().rebuild(project.projectId())
    }
}
