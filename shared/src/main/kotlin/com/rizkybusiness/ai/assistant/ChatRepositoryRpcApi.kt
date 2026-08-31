@file:Suppress("UnstableApiUsage")

package com.rizkybusiness.ai.assistant

import com.intellij.platform.project.ProjectId
import com.intellij.platform.rpc.RemoteApiProviderService
import fleet.rpc.RemoteApi
import fleet.rpc.Rpc
import fleet.rpc.remoteApiDescriptor
import kotlinx.coroutines.flow.Flow

/**
 * Interface defining the contract for managing chat messages and interactions within a chat system.
 * Provides access to the flow of messages and supports operations for sending and editing chat messages.
 */
@Rpc
interface ChatRepositoryRpcApi : RemoteApi<Unit> {
    companion object {
        suspend fun getInstance(): ChatRepositoryRpcApi {
            return RemoteApiProviderService.resolve(remoteApiDescriptor<ChatRepositoryRpcApi>())
        }
    }

    /**
     * Flow that emits a list of chat messages.
     * Updates with new messages as they are received or edited.
     */
    suspend fun getMessagesFlow(projectId: ProjectId): Flow<List<ChatMessageDto>>

    /**
     * Sends a message with the provided content.
     *
     * @param messageContent The content of the message to be sent.
     * @param attachments Full paths of `@`-mentioned files; they take priority in the
     *   context budget. Structured on purpose — never parsed back out of the text.
     */
    suspend fun sendMessage(projectId: ProjectId, messageContent: String, attachments: List<String>)

    /**
     * Flow of the files the backend currently includes as model context (open files for now).
     * Emits a new list whenever files are opened or closed on the host.
     */
    suspend fun getContextFilesFlow(projectId: ProjectId): Flow<List<ContextFileDto>>
}

