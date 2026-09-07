@file:Suppress("UnstableApiUsage")

package com.rizkybusiness.ai.assistant

import com.intellij.platform.project.ProjectId
import com.intellij.platform.rpc.RemoteApiProviderService
import fleet.rpc.RemoteApi
import fleet.rpc.Rpc
import fleet.rpc.remoteApiDescriptor
import kotlinx.coroutines.flow.Flow

/**
 * Agent Skills (agentskills.io) as seen from the client. Skills live on the host: discovery,
 * parsing and storage happen there; only catalog data (names, descriptions, locations) and
 * upload payloads cross RPC. Skill bodies are read on the host at activation time.
 */
@Rpc
interface SkillsApi : RemoteApi<Unit> {
    companion object {
        suspend fun getInstance(): SkillsApi {
            return RemoteApiProviderService.resolve(remoteApiDescriptor<SkillsApi>())
        }
    }

    /** Catalog of skills discovered on the host (project + user scopes); emits on every rescan. */
    suspend fun getStateFlow(projectId: ProjectId): Flow<SkillsStateDto>

    /**
     * Rescans every skill root on the host. Project roots are VFS-watched; the user-home roots are
     * not, so this is how the client picks up skills copied there out of band.
     */
    suspend fun refresh(projectId: ProjectId)

    /** Stores a skill uploaded from the client machine under the host's user-level skills folder. */
    suspend fun uploadSkill(projectId: ProjectId, upload: SkillUploadDto): SkillUploadResultDto

    /** Deletes an uploaded skill. Only the plugin's own user-level folder is ever touched. */
    suspend fun deleteSkill(projectId: ProjectId, name: String): SkillUploadResultDto
}
