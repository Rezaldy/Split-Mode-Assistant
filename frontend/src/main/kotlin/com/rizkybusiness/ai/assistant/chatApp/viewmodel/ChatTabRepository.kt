@file:Suppress("UnstableApiUsage")

package com.rizkybusiness.ai.assistant.chatApp.viewmodel

import com.intellij.openapi.project.Project
import com.intellij.platform.project.projectId
import fleet.rpc.client.durable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import com.rizkybusiness.ai.assistant.ChatMessage
import com.rizkybusiness.ai.assistant.ChatRepositoryRpcApi
import com.rizkybusiness.ai.assistant.ContextFileDto
import com.rizkybusiness.ai.assistant.FileRefDto
import com.rizkybusiness.ai.assistant.toChatMessage

/**
 * One chat tab's view of the backend: its own conversation (keyed by [chatId], minted by
 * the tab) plus delegation to the project-wide service for tab-independent concerns.
 * Lifetime equals the tab's — [coroutineScope] is the tab's scope.
 */
class ChatTabRepository(
    private val project: Project,
    val chatId: String,
    coroutineScope: CoroutineScope,
) : ChatRepositoryApi {

    override val messagesFlow: StateFlow<List<ChatMessage>> = flow {
        durable {
            ChatRepositoryRpcApi.getInstance().getMessagesFlow(project.projectId(), chatId).collect { fromBackend ->
                emit(fromBackend.map { messageDto -> messageDto.toChatMessage() })
            }
        }
    }.stateIn(coroutineScope, initialValue = emptyList(), started = SharingStarted.Lazily)

    override suspend fun sendMessage(messageContent: String, attachments: List<String>) {
        ChatRepositoryRpcApi.getInstance().sendMessage(project.projectId(), chatId, messageContent, attachments)
    }

    override suspend fun abortGeneration() {
        ChatRepositoryRpcApi.getInstance().abortGeneration(project.projectId(), chatId)
    }

    override suspend fun searchFiles(query: String, limit: Int): List<FileRefDto> {
        return FrontendChatRepositoryModel.getInstance(project).searchFiles(query, limit)
    }

    override val contextFilesFlow: StateFlow<List<ContextFileDto>> =
        FrontendChatRepositoryModel.getInstance(project).contextFilesFlow
}
