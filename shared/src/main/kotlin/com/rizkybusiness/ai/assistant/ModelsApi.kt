@file:Suppress("UnstableApiUsage")

package com.rizkybusiness.ai.assistant

import com.intellij.platform.rpc.RemoteApiProviderService
import fleet.rpc.RemoteApi
import fleet.rpc.Rpc
import fleet.rpc.remoteApiDescriptor
import kotlinx.coroutines.flow.Flow

/**
 * Model discovery and selection. Application-scoped on the backend (the model source is a
 * host-wide setting), so methods carry no projectId.
 */
@Rpc
interface ModelsApi : RemoteApi<Unit> {
    companion object {
        suspend fun getInstance(): ModelsApi {
            return RemoteApiProviderService.resolve(remoteApiDescriptor<ModelsApi>())
        }
    }

    /** Emits the current model list + selection; triggers discovery on first collection. */
    suspend fun getStateFlow(): Flow<ModelsStateDto>

    /** Persists the selection; the next chat request uses it (env override still wins). */
    suspend fun selectModel(name: String)

    /** Re-queries the model source. */
    suspend fun refresh()
}
