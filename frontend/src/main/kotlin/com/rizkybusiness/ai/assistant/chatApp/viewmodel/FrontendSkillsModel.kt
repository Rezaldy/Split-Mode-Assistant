@file:Suppress("UnstableApiUsage")

package com.rizkybusiness.ai.assistant.chatApp.viewmodel

import com.rizkybusiness.ai.assistant.SkillUploadDto
import com.rizkybusiness.ai.assistant.SkillUploadResultDto
import com.rizkybusiness.ai.assistant.SkillsApi
import com.rizkybusiness.ai.assistant.SkillsStateDto
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.platform.project.projectId
import fleet.rpc.client.durable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/** Client-side mirror of the host's skill catalog; the `/` popup filters [stateFlow] locally. */
@Service(Service.Level.PROJECT)
class FrontendSkillsModel(
    private val project: Project,
    coroutineScope: CoroutineScope,
) {
    companion object {
        fun getInstance(project: Project): FrontendSkillsModel =
            project.getService(FrontendSkillsModel::class.java)
    }

    val stateFlow: StateFlow<SkillsStateDto> = flow {
        durable {
            SkillsApi.getInstance().getStateFlow(project.projectId()).collect { valueFromBackend ->
                emit(valueFromBackend)
            }
        }
    }.stateIn(coroutineScope, initialValue = SkillsStateDto(), started = SharingStarted.Lazily)

    suspend fun refresh() {
        SkillsApi.getInstance().refresh(project.projectId())
    }

    suspend fun upload(upload: SkillUploadDto): SkillUploadResultDto =
        SkillsApi.getInstance().uploadSkill(project.projectId(), upload)
}
