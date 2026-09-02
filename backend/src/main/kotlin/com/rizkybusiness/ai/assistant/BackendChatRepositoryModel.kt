@file:Suppress("UnstableApiUsage")

package com.rizkybusiness.ai.assistant

import com.rizkybusiness.ai.assistant.context.ProjectContextCollector
import com.rizkybusiness.ai.assistant.models.BackendModelsService
import com.rizkybusiness.ai.assistant.ollama.OllamaChatMessage
import com.rizkybusiness.ai.assistant.ollama.OllamaClientService
import com.rizkybusiness.ai.assistant.ollama.OllamaDoneStats
import com.rizkybusiness.ai.assistant.ollama.OllamaException
import com.rizkybusiness.ai.assistant.repository.ChatMessageFactory
import com.rizkybusiness.ai.assistant.settings.AssistantSettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds every chat conversation of the project, keyed by the frontend-minted chat id (one
 * per tab). Each conversation has its own message history — and therefore its own prompt
 * history, which is the point: tabs keep the model's context scoped to one problem.
 */
@Service(Service.Level.PROJECT)
class BackendChatRepositoryModel(
    private val project: Project,
    /** Platform-provided service scope — generations run here, not as RPC-call children. */
    private val serviceScope: CoroutineScope,
) {
    companion object {
        fun getInstance(project: Project): BackendChatRepositoryModel {
            return project.getService(BackendChatRepositoryModel::class.java)
        }

        /** Throttle for pushing partial content into the messages flow (each emission crosses RPC). */
        private const val STREAM_FLUSH_INTERVAL_MS = 100L
        private const val MAX_HISTORY_MESSAGES = 20
    }

    private val chatMessageFactory = ChatMessageFactory(
        ModularPluginBackendBundle.message("chat.author.assistant"),
        ModularPluginBackendBundle.message("chat.author.user"),
    )

    private val conversations = ConcurrentHashMap<String, Conversation>()

    private fun conversation(chatId: String): Conversation =
        conversations.computeIfAbsent(chatId) { Conversation() }

    fun getMessagesFlow(chatId: String): Flow<List<ChatMessageDto>> {
        return conversation(chatId).messages.map { list -> list.map(ChatMessage::toChatMessageDto) }
    }

    suspend fun sendMessage(chatId: String, messageContent: String, attachments: List<String> = emptyList()) {
        conversation(chatId).sendMessage(messageContent, attachments)
    }

    /** Stops the conversation's in-flight generation; the partial reply stays. */
    fun abortGeneration(chatId: String) {
        conversations[chatId]?.abortGeneration()
    }

    /** Drops the conversation (its tab closed): abort the generation, free the history. */
    fun closeChat(chatId: String) {
        conversations.remove(chatId)?.abortGeneration()
    }

    private inner class Conversation {
        val messages = MutableStateFlow(
            listOf(chatMessageFactory.createAIMessage(ModularPluginBackendBundle.message("chat.greeting")))
        )

        @Volatile
        private var generationJob: Job? = null

        suspend fun sendMessage(messageContent: String, attachments: List<String>) {
            messages.value += chatMessageFactory.createUserMessage(messageContent)
            // Generation must survive the RPC call that started it: in Remote Development a
            // client<->host connection blip cancels in-flight RPC calls while the durable
            // messages flow reconnects seamlessly — pre-detach, that killed the generation
            // and showed up as a reply silently cut off mid-word (field-confirmed). So the
            // work runs on the service scope; the RPC only awaits it, cancellably.
            generationJob?.cancel()
            val job = serviceScope.launch(Dispatchers.IO) {
                try {
                    streamAssistantResponse(messageContent, attachments)
                } catch (e: CancellationException) {
                    thisLogger().info("Chat generation cancelled (user abort, tab closed, or backend shutdown)")
                    messages.value = messages.value.filter { !it.isAIThinkingMessage() }
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
            generationJob = job
            job.join()
        }

        fun abortGeneration() {
            generationJob?.cancel()
        }

        private suspend fun streamAssistantResponse(question: String, attachments: List<String>) {
            messages.value += chatMessageFactory
                .createAIThinkingMessage(ModularPluginBackendBundle.message("chat.thinking"))

            val model = BackendModelsService.getInstance().resolveChatModel()
            val requestMessages = buildRequestMessages(question, attachments)

            val streamedMessage = chatMessageFactory.createAIMessage("")
            val content = StringBuilder()
            val thinking = StringBuilder()
            var lastFlush = 0L
            var doneStats: OllamaDoneStats? = null
            val numCtx = AssistantSettings.getInstance().contextTokens
            val requestThinking = BackendModelsService.getInstance().supportsThinking(model)
            try {
                OllamaClientService.getInstance().client()
                    .chatStream(
                        model,
                        requestMessages,
                        contextTokens = numCtx,
                        requestThinking = requestThinking,
                        onDone = { doneStats = it },
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
            } finally {
                // On failure or cancel the throttle above has dropped up to 100ms of received
                // tokens from the display — flush them so the visible cut is the real one.
                if (content.isNotEmpty() || thinking.isNotEmpty()) {
                    upsertAssistantMessage(
                        streamedMessage.copy(content = content.toString(), thinking = thinking.toString())
                    )
                }
            }
            val stats = doneStats
            val usedTokens = (stats?.promptTokens ?: 0) + (stats?.replyTokens ?: 0)
            val nearLimit = usedTokens >= (numCtx * 98) / 100
            if (stats?.reason == "length") {
                content.append("\n\n*")
                    .append(ModularPluginBackendBundle.message("chat.truncated"))
                    .append("*")
            } else if (nearLimit) {
                // Ollama sometimes ends a context-exhausted stream with a clean "stop" —
                // the counts give it away, so say so instead of leaving a silent mid-word cut.
                content.append("\n\n*")
                    .append(ModularPluginBackendBundle.message("chat.context.full", usedTokens, numCtx))
                    .append("*")
            }
            upsertAssistantMessage(
                streamedMessage.copy(
                    content = content.toString(),
                    thinking = thinking.toString(),
                    promptTokens = stats?.promptTokens ?: 0,
                    replyTokens = stats?.replyTokens ?: 0,
                    contextLimit = numCtx,
                )
            )
        }

        /** History (this conversation only) prefixed by a system message carrying the project context. */
        private suspend fun buildRequestMessages(question: String, attachments: List<String>): List<OllamaChatMessage> {
            val history = messages.value
                .filter { it.isTextMessage() && it.content.isNotBlank() }
                .takeLast(MAX_HISTORY_MESSAGES)
                .map { OllamaChatMessage(role = if (it.isMyMessage) "user" else "assistant", content = it.content) }
            val context = ProjectContextCollector.getInstance(project)
                .collect(question = question, mentionPaths = attachments)
            val systemContent = buildString {
                append(AssistantSettings.getInstance().effectiveChatSystemPrompt)
                if (context.isNotBlank()) {
                    append("\n\nProject context (each block is labeled with its source — mentioned, selection, open, or retrieved):\n")
                    append(context)
                }
            }
            return listOf(OllamaChatMessage("system", systemContent)) + history
        }

        /** Drops the thinking placeholder and inserts or updates the assistant message by id. */
        private fun upsertAssistantMessage(message: ChatMessage) {
            val withoutThinking = messages.value.filter { !it.isAIThinkingMessage() }
            messages.value = if (withoutThinking.any { it.id == message.id }) {
                withoutThinking.map { existing -> if (existing.id == message.id) message else existing }
            } else {
                withoutThinking + message
            }
        }
    }
}
