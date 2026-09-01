package com.rizkybusiness.ai.assistant

import com.rizkybusiness.ai.assistant.ChatMessage.ChatMessageType.AI_THINKING
import com.rizkybusiness.ai.assistant.ChatMessage.ChatMessageType.TEXT
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*

private val timeFormatter: DateTimeFormatter? = DateTimeFormatter.ofPattern("HH:mm")

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val author: String,
    val isMyMessage: Boolean = false,
    val timestamp: LocalDateTime = LocalDateTime.now(),
    val type: ChatMessageType = TEXT,
    /** The model's reasoning stream, when the model exposes one; never sent back as history. */
    val thinking: String = "",
    /**
     * Token usage reported by the model source for the request that produced this message
     * (assistant messages only; 0 = unknown). [promptTokens] excludes server-side
     * KV-cache hits, so prompt+reply is a lower bound on context-window usage.
     */
    val promptTokens: Int = 0,
    val replyTokens: Int = 0,
    /** The num_ctx the request was sent with; 0 = unknown. */
    val contextLimit: Int = 0,
) : Searchable {

    enum class ChatMessageType {
        AI_THINKING,
        TEXT,
        ERROR;
    }

    @JvmOverloads
    fun formattedTime(dateTimeFormatter: DateTimeFormatter? = timeFormatter): String {
        return timestamp.format(dateTimeFormatter)
    }


    fun isTextMessage(): Boolean = this.type == TEXT

    fun isAIThinkingMessage(): Boolean = this.type == AI_THINKING

    fun isErrorMessage(): Boolean = this.type == ChatMessageType.ERROR

    override fun matches(query: String): Boolean {
        if (query.isBlank()) return false

        return content.contains(query, ignoreCase = true)
    }
}
