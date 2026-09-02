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
import kotlinx.coroutines.launch
import com.rizkybusiness.ai.assistant.ChatRepositoryRpcApi
import com.rizkybusiness.ai.assistant.ContextFileDto
import com.rizkybusiness.ai.assistant.FileRefDto
import com.rizkybusiness.ai.assistant.FileSearchApi

/**
 * Project-wide chat plumbing shared by every chat tab: the context-files bar flow, file
 * search for the `@` popup, and backend cleanup for closed tabs. Per-conversation state
 * lives in [ChatTabRepository] — one instance per tab.
 */
@Service(Level.PROJECT)
class FrontendChatRepositoryModel(
    private val project: Project,
    private val coroutineScope: CoroutineScope
) {
    companion object {
        fun getInstance(project: Project): FrontendChatRepositoryModel {
            return project.getService(FrontendChatRepositoryModel::class.java)
        }
    }

    suspend fun searchFiles(query: String, limit: Int): List<FileRefDto> {
        return FileSearchApi.getInstance().search(project.projectId(), query, limit)
    }

    val contextFilesFlow: StateFlow<List<ContextFileDto>> = flow {
        durable {
            ChatRepositoryRpcApi.getInstance().getContextFilesFlow(project.projectId()).collect { valueFromBackend ->
                emit(valueFromBackend)
            }
        }
    }.stateIn(coroutineScope, initialValue = emptyList(), started = SharingStarted.Lazily)

    /**
     * Tells the backend to drop a closed tab's conversation. Launched on the service scope:
     * the tab's own scope is being disposed at that moment and could not carry the call.
     */
    fun closeChatAsync(chatId: String) {
        coroutineScope.launch {
            runCatching { ChatRepositoryRpcApi.getInstance().closeChat(project.projectId(), chatId) }
        }
    }
}
