package com.transtrend.ai.assistant

import kotlinx.serialization.Serializable
import java.time.LocalDateTime

/** Snapshot of the model source's discovered models and current selection. */
@Serializable
data class ModelsStateDto(
    val models: List<String> = emptyList(),
    val selectedModel: String? = null,
    /** True when OLLAMA_MODEL is set — selection is fixed by the environment. */
    val envOverride: Boolean = false,
    val error: String? = null,
)

/** A file the backend will include as model context. Plain data only — never platform objects. */
@Serializable
data class ContextFileDto(
    val path: String,
    val fileName: String,
)

@Serializable
data class ChatMessageDto(
    val id: String,
    val content: String,
    val author: String,
    val isMyMessage: Boolean,
    @Serializable(with = LocalDateTimeSerializer::class)
    val timestamp: LocalDateTime,
    val type: ChatMessage.ChatMessageType
)

fun ChatMessageDto.toChatMessage(): ChatMessage {
    return ChatMessage(
        id = id,
        content = content,
        author = author,
        isMyMessage = isMyMessage,
        timestamp = timestamp,
        type = type
    )
}

fun ChatMessage.toChatMessageDto(): ChatMessageDto {
    return ChatMessageDto(
        id = id,
        content = content,
        author = author,
        isMyMessage = isMyMessage,
        timestamp = timestamp,
        type = type
    )
}