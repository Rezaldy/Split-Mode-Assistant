@file:Suppress("UnstableApiUsage")

package com.transtrend.ai.assistant

import com.intellij.platform.project.ProjectId
import com.intellij.platform.rpc.RemoteApiProviderService
import fleet.rpc.RemoteApi
import fleet.rpc.Rpc
import fleet.rpc.remoteApiDescriptor

/** Fuzzy project-file search powering the `@` mention popup. */
@Rpc
interface FileSearchApi : RemoteApi<Unit> {
    companion object {
        suspend fun getInstance(): FileSearchApi {
            return RemoteApiProviderService.resolve(remoteApiDescriptor<FileSearchApi>())
        }
    }

    /** Returns at most [limit] (≤ 20) project files fuzzy-matched on file name, then path. */
    suspend fun search(projectId: ProjectId, query: String, limit: Int): List<FileRefDto>
}
