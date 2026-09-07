package com.rizkybusiness.ai.assistant.skills

import com.rizkybusiness.ai.assistant.ModularPluginBackendBundle
import com.rizkybusiness.ai.assistant.SkillUploadDto
import com.rizkybusiness.ai.assistant.SkillUploadResultDto
import com.rizkybusiness.ai.assistant.SkillsApi
import com.rizkybusiness.ai.assistant.SkillsStateDto
import com.intellij.platform.project.ProjectId
import com.intellij.platform.project.findProjectOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext

class BackendSkillsApi : SkillsApi {
    override suspend fun getStateFlow(projectId: ProjectId): Flow<SkillsStateDto> {
        val backendProject = projectId.findProjectOrNull() ?: return emptyFlow()
        return SkillDiscoveryService.getInstance(backendProject).stateDtoFlow()
    }

    override suspend fun refresh(projectId: ProjectId) {
        val backendProject = projectId.findProjectOrNull() ?: return
        SkillDiscoveryService.getInstance(backendProject).refreshNow()
    }

    override suspend fun uploadSkill(projectId: ProjectId, upload: SkillUploadDto): SkillUploadResultDto {
        val backendProject = projectId.findProjectOrNull() ?: return projectGone()
        val result = withContext(Dispatchers.IO) { SkillStore(SkillLocations.uploadRoot()).write(upload) }
        if (result is SkillStoreResult.Ok) SkillDiscoveryService.getInstance(backendProject).refreshNow()
        return result.toDto()
    }

    override suspend fun deleteSkill(projectId: ProjectId, name: String): SkillUploadResultDto {
        val backendProject = projectId.findProjectOrNull() ?: return projectGone()
        val result = withContext(Dispatchers.IO) { SkillStore(SkillLocations.uploadRoot()).delete(name) }
        if (result is SkillStoreResult.Ok) SkillDiscoveryService.getInstance(backendProject).refreshNow()
        return result.toDto()
    }

    private fun projectGone() = SkillUploadResultDto(
        success = false,
        code = "error",
        message = ModularPluginBackendBundle.message("skills.upload.error", "project closed"),
    )

    companion object {
        fun SkillStoreResult.toDto(): SkillUploadResultDto = when (this) {
            is SkillStoreResult.Ok -> SkillUploadResultDto(
                true, "ok", ModularPluginBackendBundle.message("skills.upload.ok", name), name)
            is SkillStoreResult.Exists -> SkillUploadResultDto(
                false, "exists", ModularPluginBackendBundle.message("skills.upload.exists", name), name)
            is SkillStoreResult.Invalid -> SkillUploadResultDto(
                false, "invalid", ModularPluginBackendBundle.message("skills.upload.invalid.$code", detail))
            is SkillStoreResult.TooLarge -> SkillUploadResultDto(
                false, "too-large", ModularPluginBackendBundle.message("skills.upload.too.large", limitKb))
            is SkillStoreResult.Failed -> SkillUploadResultDto(
                false, "error", ModularPluginBackendBundle.message("skills.upload.error", message))
            is SkillStoreResult.NotFound -> SkillUploadResultDto(
                false, "error", ModularPluginBackendBundle.message("skills.delete.missing", name), name)
        }
    }
}
