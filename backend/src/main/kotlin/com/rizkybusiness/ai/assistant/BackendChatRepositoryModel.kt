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
import com.rizkybusiness.ai.assistant.skills.SkillDiscoveryService
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.debug
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
import java.util.concurrent.atomic.AtomicInteger

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

        /** Separate from the 24k-char project-context budget; ~3k tokens for all activated skills. */
        private const val SKILLS_BUDGET_CHARS = 12_000
    }

    private val chatMessageFactory = ChatMessageFactory(
        ModularPluginBackendBundle.message("chat.author.assistant"),
        ModularPluginBackendBundle.message("chat.author.user"),
    )

    private val conversations = ConcurrentHashMap<String, Conversation>()

    private fun conversation(chatId: String): Conversation =
        conversations.computeIfAbsent(chatId) { Conversation(chatId) }

    fun getMessagesFlow(chatId: String): Flow<List<ChatMessageDto>> {
        return conversation(chatId).messages.map { list -> list.map(ChatMessage::toChatMessageDto) }
    }

    suspend fun sendMessage(
        chatId: String,
        messageContent: String,
        attachments: List<String> = emptyList(),
        skills: List<String> = emptyList(),
    ) {
        conversation(chatId).sendMessage(messageContent, attachments, skills)
    }

    /** Stops the conversation's in-flight generation; the partial reply stays. */
    fun abortGeneration(chatId: String) {
        conversations[chatId]?.abortGeneration()
    }

    /** Drops the conversation (its tab closed): abort the generation, free the history. */
    fun closeChat(chatId: String) {
        conversations.remove(chatId)?.abortGeneration()
    }

    /** Assembled request plus the size of its project-context block (for the start log line). */
    private class RequestBuild(val messages: List<OllamaChatMessage>, val contextChars: Int)

    private inner class Conversation(private val chatId: String) {
        val messages = MutableStateFlow(
            listOf(chatMessageFactory.createAIMessage(ModularPluginBackendBundle.message("chat.greeting")))
        )

        @Volatile
        private var generationJob: Job? = null

        /**
         * Skills the user invoked in this conversation, in activation order. Re-injected into
         * the system message on every turn: history is capped at [MAX_HISTORY_MESSAGES], so
         * instructions that only lived in an old turn would silently fall out of the prompt.
         */
        private val activatedSkills = LinkedHashSet<String>()

        /** Counts generations of this conversation; with the chat id it forms the `gen=` log id. */
        private val generationCounter = AtomicInteger()

        suspend fun sendMessage(messageContent: String, attachments: List<String>, skills: List<String>) {
            messages.value += chatMessageFactory.createUserMessage(messageContent)
            if (skills.isNotEmpty()) {
                val known = SkillDiscoveryService.getInstance(project).enabledSkills().map { it.name }.toSet()
                val unknown = skills.firstOrNull { it !in known }
                if (unknown != null) {
                    // The client resolves tokens against its catalog copy, so this only happens
                    // when a skill was disabled or deleted in between — say so, don't guess.
                    messages.value += chatMessageFactory.createErrorMessage(
                        ModularPluginBackendBundle.message("chat.skill.unknown", unknown)
                    )
                    return
                }
                activatedSkills += skills
            }
            // Generation must survive the RPC call that started it: in Remote Development a
            // client<->host connection blip cancels in-flight RPC calls while the durable
            // messages flow reconnects seamlessly — pre-detach, that killed the generation
            // and showed up as a reply silently cut off mid-word (field-confirmed). So the
            // work runs on the service scope; the RPC only awaits it, cancellably.
            generationJob?.cancel()
            val genId = "${chatId.take(8)}/${generationCounter.incrementAndGet()}"
            val job = serviceScope.launch(Dispatchers.IO) {
                try {
                    streamAssistantResponse(genId, messageContent, attachments)
                } catch (e: CancellationException) {
                    thisLogger().info("Chat generation cancelled: gen=$genId (user abort, tab closed, or backend shutdown)")
                    messages.value = messages.value.filter { !it.isAIThinkingMessage() }
                    throw e
                } catch (e: OllamaException) {
                    upsertAssistantMessage(
                        chatMessageFactory.createErrorMessage(
                            ModularPluginBackendBundle.message("chat.error", e.message.orEmpty())
                        )
                    )
                } catch (e: Exception) {
                    thisLogger().warn("Chat generation failed: gen=$genId", e)
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

        private suspend fun streamAssistantResponse(genId: String, question: String, attachments: List<String>) {
            messages.value += chatMessageFactory
                .createAIThinkingMessage(ModularPluginBackendBundle.message("chat.thinking"))

            val model = BackendModelsService.getInstance().resolveChatModel()
            val request = buildRequestMessages(question, attachments)
            val requestMessages = request.messages
            // Counts and sizes only, never the prompt itself (plugin-logging skill).
            thisLogger().info(
                "Chat generation started: gen=$genId model='$model' messages=${requestMessages.size} " +
                    "attachments=${attachments.size} context=${request.contextChars}"
            )

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
                        logTag = "gen=$genId",
                    )
                    .collect { token ->
                        if (token.isThinking) thinking.append(token.text) else content.append(token.text)
                        val now = System.currentTimeMillis()
                        if (now - lastFlush >= STREAM_FLUSH_INTERVAL_MS) {
                            lastFlush = now
                            upsertAssistantMessage(
                                streamedMessage.copy(
                                    content = content.toString(),
                                    thinking = thinking.toString(),
                                    isStreaming = true,
                                )
                            )
                        }
                    }
            } finally {
                // On failure or cancel the throttle above has dropped up to 100ms of received
                // tokens from the display — flush them so the visible cut is the real one.
                // Runs on EVERY exit, so it is also what clears isStreaming: the frontend's
                // Stop button stays visible exactly as long as some message claims to stream.
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
        private suspend fun buildRequestMessages(question: String, attachments: List<String>): RequestBuild {
            val history = messages.value
                .filter { it.isTextMessage() && it.content.isNotBlank() }
                .takeLast(MAX_HISTORY_MESSAGES)
                .map { OllamaChatMessage(role = if (it.isMyMessage) "user" else "assistant", content = it.content) }
            val context = ProjectContextCollector.getInstance(project)
                .collect(question = question, mentionPaths = attachments)
            val skillBlocks = buildSkillBlocks()
            val skillCatalog = buildSkillCatalog()
            val systemContent = buildString {
                append(AssistantSettings.getInstance().effectiveChatSystemPrompt)
                if (skillBlocks.isNotBlank()) {
                    append("\n\n").append(skillBlocks)
                }
                if (skillCatalog.isNotBlank()) {
                    append("\n\n").append(skillCatalog)
                }
                if (context.isNotBlank()) {
                    append("\n\nProject context (each block is labeled with its source — mentioned, selection, open, or retrieved):\n")
                    append(context)
                }
            }
            return RequestBuild(listOf(OllamaChatMessage("system", systemContent)) + history, context.length)
        }

        /**
         * One `<skill_content>` block per activated skill, bodies re-read from disk each turn,
         * under their own budget ([SKILLS_BUDGET_CHARS]) so a large skill cannot starve the
         * project context. A skill deleted or disabled mid-conversation drops out and gets a
         * one-time error bubble ([ModularPluginBackendBundle.message] `chat.skill.dropped`); it
         * stays out of [activatedSkills] until re-invoked with `/name`.
         */
        private suspend fun buildSkillBlocks(): String {
            if (activatedSkills.isEmpty()) return ""
            val service = SkillDiscoveryService.getInstance(project)
            var remaining = SKILLS_BUDGET_CHARS
            val blocks = StringBuilder()
            val dropped = mutableListOf<String>()
            for (name in activatedSkills.toList()) {
                if (remaining <= 0) break
                val body = service.readBody(name)
                if (body == null) {
                    thisLogger().info("Skill '$name' is no longer available; dropped from the prompt")
                    dropped += name
                    continue
                }
                val fits = body.text.length <= remaining
                val text = if (fits) body.text else body.text.take(remaining)
                remaining -= text.length
                blocks.append("<skill_content name=\"").append(name).append("\">\n")
                    .append("The user invoked this skill with /").append(name)
                    .append(". Follow these instructions for the rest of the conversation.\n\n")
                    .append(text)
                if (!fits || body.truncated) {
                    blocks.append("\n\n[skill instructions truncated to fit the prompt budget]")
                }
                blocks.append("\n</skill_content>\n")
            }
            for (name in dropped) {
                messages.value += chatMessageFactory.createErrorMessage(
                    ModularPluginBackendBundle.message("chat.skill.dropped", name)
                )
                activatedSkills -= name
            }
            thisLogger().debug { "Injecting ${activatedSkills.size} skill(s) (${blocks.length} chars): $activatedSkills" }
            return blocks.toString().trim()
        }

        /** Optional tier-1 catalog (setting-gated): lets the model suggest a skill; it cannot load one itself. */
        private suspend fun buildSkillCatalog(): String {
            if (!AssistantSettings.getInstance().skillsCatalogInPrompt) return ""
            val skills = SkillDiscoveryService.getInstance(project).enabledSkills()
                .filter { it.name !in activatedSkills }
            if (skills.isEmpty()) return ""
            return buildString {
                append("<available_skills>\n")
                append("The user can load any of these skills by starting a message with /skill-name. ")
                append("Suggest one when it clearly fits the task; you cannot load them yourself.\n")
                for (skill in skills) {
                    append("- /").append(skill.name).append(": ")
                        .append(skill.description.replace('\n', ' ').trim()).append('\n')
                }
                append("</available_skills>")
            }
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
