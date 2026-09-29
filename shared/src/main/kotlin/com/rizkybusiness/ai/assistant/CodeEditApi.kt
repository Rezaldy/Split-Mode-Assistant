@file:Suppress("UnstableApiUsage")

package com.rizkybusiness.ai.assistant

import com.intellij.platform.project.ProjectId
import com.intellij.platform.rpc.RemoteApiProviderService
import fleet.rpc.RemoteApi
import fleet.rpc.Rpc
import fleet.rpc.remoteApiDescriptor

/** Applies a code block the assistant proposed: preview as a diff first, then write on accept. */
@Rpc
interface CodeEditApi : RemoteApi<Unit> {
    companion object {
        suspend fun getInstance(): CodeEditApi {
            return RemoteApiProviderService.resolve(remoteApiDescriptor<CodeEditApi>())
        }
    }

    /** Resolves [path] inside the project and applies [block] (SEARCH/REPLACE hunks or whole content) in memory. */
    suspend fun preview(projectId: ProjectId, path: String, block: String): EditPreviewDto

    /** Writes [newText] to [path]; refuses when the file no longer matches [expectedOldText] (null = must not exist). */
    suspend fun apply(projectId: ProjectId, path: String, expectedOldText: String?, newText: String): EditResultDto
}
