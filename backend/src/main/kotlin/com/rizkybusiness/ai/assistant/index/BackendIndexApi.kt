package com.rizkybusiness.ai.assistant.index

import com.rizkybusiness.ai.assistant.IndexApi
import com.rizkybusiness.ai.assistant.IndexStatusDto
import com.intellij.platform.project.ProjectId
import com.intellij.platform.project.findProjectOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class BackendIndexApi : IndexApi {
    override suspend fun getStatusFlow(projectId: ProjectId): Flow<IndexStatusDto> {
        val backendProject = projectId.findProjectOrNull() ?: return emptyFlow()
        return ProjectIndexService.getInstance(backendProject).statusDtoFlow()
    }

    override suspend fun rebuild(projectId: ProjectId) {
        val backendProject = projectId.findProjectOrNull() ?: return
        ProjectIndexService.getInstance(backendProject).rebuild()
    }
}
