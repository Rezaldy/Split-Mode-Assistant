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
     * Flow that emits the message list of one conversation ([chatId] — an opaque id minted
     * by the frontend, one per chat tab). The conversation is created lazily on first use;
     * each conversation has its own history, so its prompt context stays scoped to it.
     */
    suspend fun getMessagesFlow(projectId: ProjectId, chatId: String): Flow<List<ChatMessageDto>>

    /**
     * Sends a message into the conversation [chatId].
     *
     * @param messageContent The content of the message to be sent.
     * @param attachments Full paths of `@`-mentioned files; they take priority in the
     *   context budget. Structured on purpose — never parsed back out of the text.
     */
    suspend fun sendMessage(projectId: ProjectId, chatId: String, messageContent: String, attachments: List<String>)

    /**
     * Cancels the in-flight generation of conversation [chatId], if any. Generation runs
     * on a backend-owned scope (it survives the [sendMessage] call being cancelled by a
     * connection blip), so an explicit abort is the only way the Stop button can stop it.
     */
    suspend fun abortGeneration(projectId: ProjectId, chatId: String)

    /** Drops the conversation's backend state (called when its tab closes); aborts its generation. */
    suspend fun closeChat(projectId: ProjectId, chatId: String)

    /**
     * Flow of the files the backend currently includes as model context (open files for now).
     * Emits a new list whenever files are opened or closed on the host.
     */
    suspend fun getContextFilesFlow(projectId: ProjectId): Flow<List<ContextFileDto>>
}

