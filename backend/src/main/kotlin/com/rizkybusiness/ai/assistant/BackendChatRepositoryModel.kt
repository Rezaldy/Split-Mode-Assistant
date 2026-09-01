@file:Suppress("UnstableApiUsage")

package com.rizkybusiness.ai.assistant

import com.rizkybusiness.ai.assistant.context.ProjectContextCollector
import com.rizkybusiness.ai.assistant.models.BackendModelsService
import com.rizkybusiness.ai.assistant.ollama.OllamaChatMessage
import com.rizkybusiness.ai.assistant.ollama.OllamaClientService
import com.rizkybusiness.ai.assistant.ollama.OllamaException
import com.rizkybusiness.ai.assistant.repository.ChatMessageFactory
import com.rizkybusiness.ai.assistant.settings.AssistantSettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Service(Service.Level.PROJECT)
class BackendChatRepositoryModel(private val project: Project) {
    companion object {
        fun getInstance(project: Project): BackendChatRepositoryModel {
            return project.getService(BackendChatRepositoryModel::class.java)
        }

        /** Throttle for pushing partial content into the messages flow (each emission crosses RPC). */
        private const val STREAM_FLUSH_INTERVAL_MS = 100L
        private const val MAX_HISTORY_MESSAGES = 20
        private const val SYSTEM_PROMPT =
            "You are a concise coding assistant embedded in a JetBrains IDE. " +
                "Use the provided project context when it is relevant to the question. " +
                "Snippets labeled [retrieved] were found by semantic search of the project index."
    }

    private val chatMessageFactory = ChatMessageFactory(
        ModularPluginBackendBundle.message("chat.author.assistant"),
        ModularPluginBackendBundle.message("chat.author.user"),
    )
    private val _messages = MutableStateFlow(
        listOf(chatMessageFactory.createAIMessage(ModularPluginBackendBundle.message("chat.greeting")))
    )

    fun getMessagesFlow(): Flow<List<ChatMessageDto>> {
        return _messages.map { messagesList -> messagesList.map(ChatMessage::toChatMessageDto) }
    }

    suspend fun sendMessage(messageContent: String, attachments: List<String> = emptyList()) {
        withContext(Dispatchers.IO) {
            _messages.value += chatMessageFactory.createUserMessage(messageContent)
            try {
                streamAssistantResponse(messageContent, attachments)
            } catch (e: CancellationException) {
                _messages.value = _messages.value.filter { !it.isAIThinkingMessage() }
                throw e
            } catch (e: OllamaException) {
                upsertAssistantMessage(
                    chatMessageFactory.createErrorMessage(
                        ModularPluginBackendBundle.message("chat.error", e.message.orEmpty())
                    )
                )
            } catch (e: Exception) {
                thisLogger().warn("Chat generation failed", e)
                upsertAssistantMessage(
                    chatMessageFactory.createErrorMessage(
                        ModularPluginBackendBundle.message("chat.error", e.message ?: e.javaClass.simpleName)
                    )
                )
            }
        }
    }

    private suspend fun streamAssistantResponse(question: String, attachments: List<String>) {
        _messages.value += chatMessageFactory
            .createAIThinkingMessage(ModularPluginBackendBundle.message("chat.thinking"))

        val model = BackendModelsService.getInstance().resolveChatModel()
        val requestMessages = buildRequestMessages(question, attachments)

        val streamedMessage = chatMessageFactory.createAIMessage("")
        val content = StringBuilder()
        val thinking = StringBuilder()
        var lastFlush = 0L
        var truncatedByLimit = false
        val requestThinking = BackendModelsService.getInstance().supportsThinking(model)
        OllamaClientService.getInstance().client()
            .chatStream(
                model,
                requestMessages,
                contextTokens = AssistantSettings.getInstance().contextTokens,
                requestThinking = requestThinking,
                onDone = { reason -> truncatedByLimit = reason == "length" },
            )
            .collect { token ->
                if (token.isThinking) thinking.append(token.text) else content.append(token.text)
                val now = System.currentTimeMillis()
                if (now - lastFlush >= STREAM_FLUSH_INTERVAL_MS) {
                    lastFlush = now
                    upsertAssistantMessage(
                        streamedMessage.copy(content = content.toString(), thinking = thinking.toString())
                    )
                }
            }
        if (truncatedByLimit) {
            content.append("\n\n*")
                .append(ModularPluginBackendBundle.message("chat.truncated"))
                .append("*")
        }
        upsertAssistantMessage(
            streamedMessage.copy(content = content.toString(), thinking = thinking.toString())
        )
    }

    /** History (text messages only) prefixed by a system message carrying the project context. */
    private suspend fun buildRequestMessages(question: String, attachments: List<String>): List<OllamaChatMessage> {
        val history = _messages.value
            .filter { it.isTextMessage() && it.content.isNotBlank() }
            .takeLast(MAX_HISTORY_MESSAGES)
            .map { OllamaChatMessage(role = if (it.isMyMessage) "user" else "assistant", content = it.content) }
        val context = ProjectContextCollector.getInstance(project)
            .collect(question = question, mentionPaths = attachments)
        val systemContent = buildString {
            append(SYSTEM_PROMPT)
            if (context.isNotBlank()) {
                append("\n\nProject context (each block is labeled with its source — mentioned, selection, open, or retrieved):\n")
                append(context)
            }
        }
        return listOf(OllamaChatMessage("system", systemContent)) + history
    }

    /** Drops the thinking placeholder and inserts or updates the assistant message by id. */
    private fun upsertAssistantMessage(message: ChatMessage) {
        val withoutThinking = _messages.value.filter { !it.isAIThinkingMessage() }
        _messages.value = if (withoutThinking.any { it.id == message.id }) {
            withoutThinking.map { existing -> if (existing.id == message.id) message else existing }
        } else {
            withoutThinking + message
        }
    }
}
