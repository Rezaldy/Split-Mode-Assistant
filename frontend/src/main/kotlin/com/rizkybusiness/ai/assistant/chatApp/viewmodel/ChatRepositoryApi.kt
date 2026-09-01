package com.rizkybusiness.ai.assistant.chatApp.viewmodel

import kotlinx.coroutines.flow.StateFlow
import com.rizkybusiness.ai.assistant.ChatMessage
import com.rizkybusiness.ai.assistant.ContextFileDto
import com.rizkybusiness.ai.assistant.FileRefDto

/**
 * Interface defining the contract for managing chat messages and interactions within a chat system.
 * Provides access to the flow of messages and supports operations for sending and editing chat messages.
 */
interface ChatRepositoryApi {
    /**
     * Flow that emits a list of chat messages.
     * Updates with new messages as they are received or edited.
     */
    val messagesFlow: StateFlow<List<ChatMessage>>

    /**
     * Sends a message with the provided content.
     *
     * @param messageContent The content of the message to be sent.
     * @param attachments Full paths of `@`-mentioned files (structured, not parsed from text).
     */
    suspend fun sendMessage(messageContent: String, attachments: List<String> = emptyList())

    /** Stops the backend's in-flight generation (the Stop button); the partial reply stays. */
    suspend fun abortGeneration()

    /** Fuzzy project-file search for the `@` popup; resolved on the backend. */
    suspend fun searchFiles(query: String, limit: Int = 20): List<FileRefDto>

    /** Files the backend currently includes as model context (open files on the host). */
    val contextFilesFlow: StateFlow<List<ContextFileDto>>
}