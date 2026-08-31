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
    val type: ChatMessageType = TEXT
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
