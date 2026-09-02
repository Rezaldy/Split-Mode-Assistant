@file:Suppress("UnstableApiUsage")

package com.rizkybusiness.ai.assistant

import com.rizkybusiness.ai.assistant.context.ProjectContextCollector
import com.intellij.platform.project.ProjectId
import com.intellij.platform.project.findProjectOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class BackendChatRepositoryRpcApi : ChatRepositoryRpcApi {
    override suspend fun getContextFilesFlow(projectId: ProjectId): Flow<List<ContextFileDto>> {
        val backendProject = projectId.findProjectOrNull() ?: return emptyFlow()
        return ProjectContextCollector.getInstance(backendProject).contextFiles
    }

    override suspend fun getMessagesFlow(projectId: ProjectId, chatId: String): Flow<List<ChatMessageDto>> {
        val backendProject = projectId.findProjectOrNull() ?: return emptyFlow()
        return BackendChatRepositoryModel.getInstance(backendProject).getMessagesFlow(chatId)
    }

    override suspend fun sendMessage(
        projectId: ProjectId,
        chatId: String,
        messageContent: String,
        attachments: List<String>
    ) {
        val backendProject = projectId.findProjectOrNull() ?: return
        return BackendChatRepositoryModel.getInstance(backendProject).sendMessage(chatId, messageContent, attachments)
    }

    override suspend fun abortGeneration(projectId: ProjectId, chatId: String) {
        val backendProject = projectId.findProjectOrNull() ?: return
        BackendChatRepositoryModel.getInstance(backendProject).abortGeneration(chatId)
    }

    override suspend fun closeChat(projectId: ProjectId, chatId: String) {
        val backendProject = projectId.findProjectOrNull() ?: return
        BackendChatRepositoryModel.getInstance(backendProject).closeChat(chatId)
    }
}
