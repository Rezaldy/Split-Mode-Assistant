@file:Suppress("UnstableApiUsage")

package com.rizkybusiness.ai.assistant.chatApp.viewmodel

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level
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
import com.rizkybusiness.ai.assistant.FileSearchApi
import com.rizkybusiness.ai.assistant.toChatMessage

@Service(Level.PROJECT)
class FrontendChatRepositoryModel(
    private val project: Project,
    coroutineScope: CoroutineScope
) : ChatRepositoryApi {
    companion object {
        fun getInstance(project: Project): FrontendChatRepositoryModel {
            return project.getService(FrontendChatRepositoryModel::class.java)
        }
    }

    override val messagesFlow: StateFlow<List<ChatMessage>> = flow {
        durable {
            ChatRepositoryRpcApi.getInstance().getMessagesFlow(project.projectId()).collect { valueFromBackend ->
                val mappedValue = valueFromBackend.map { messageDto -> messageDto.toChatMessage() }
                emit(mappedValue)
            }
        }
    }.stateIn(coroutineScope, initialValue = emptyList(), started = SharingStarted.Lazily)

    override suspend fun sendMessage(messageContent: String, attachments: List<String>) {
        ChatRepositoryRpcApi.getInstance().sendMessage(project.projectId(), messageContent, attachments)
    }

    override suspend fun searchFiles(query: String, limit: Int): List<FileRefDto> {
        return FileSearchApi.getInstance().search(project.projectId(), query, limit)
    }

    override val contextFilesFlow: StateFlow<List<ContextFileDto>> = flow {
        durable {
            ChatRepositoryRpcApi.getInstance().getContextFilesFlow(project.projectId()).collect { valueFromBackend ->
                emit(valueFromBackend)
            }
        }
    }.stateIn(coroutineScope, initialValue = emptyList(), started = SharingStarted.Lazily)
}