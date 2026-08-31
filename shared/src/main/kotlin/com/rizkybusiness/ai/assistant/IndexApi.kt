@file:Suppress("UnstableApiUsage")

package com.rizkybusiness.ai.assistant

import com.intellij.platform.project.ProjectId
import com.intellij.platform.rpc.RemoteApiProviderService
import fleet.rpc.RemoteApi
import fleet.rpc.Rpc
import fleet.rpc.remoteApiDescriptor
import kotlinx.coroutines.flow.Flow

/**
 * Project-index status + control for the chat UI. Status only — vectors, chunks and
 * file contents never cross RPC.
 */
@Rpc
interface IndexApi : RemoteApi<Unit> {
    companion object {
        suspend fun getInstance(): IndexApi {
            return RemoteApiProviderService.resolve(remoteApiDescriptor<IndexApi>())
        }
    }

    /** Emits on every index state or sync change (building progress, pending edits, errors). */
    suspend fun getStatusFlow(projectId: ProjectId): Flow<IndexStatusDto>

    suspend fun rebuild(projectId: ProjectId)
}
