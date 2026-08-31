package com.rizkybusiness.ai.assistant

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Snapshot of the model source's discovered models and current selection. */
@Serializable
data class ModelsStateDto(
    val models: List<String> = emptyList(),
    val selectedModel: String? = null,
    /** True when OLLAMA_MODEL is set — selection is fixed by the environment. */
    val envOverride: Boolean = false,
    val error: String? = null,
)

/** A project file offered by the `@` mention search. */
@Serializable
data class FileRefDto(
    val path: String,
    val presentablePath: String,
    val fileName: String,
)

/** A file the backend will include as model context. Plain data only — never platform objects. */
@Serializable
data class ContextFileDto(
    val path: String,
    val fileName: String,
    /** "open" or "retrieved"; default keeps the wire format compatible. */
    val source: String = "open",
)

@Serializable
data class ChatMessageDto(
    val id: String,
    val content: String,
    val author: String,
    val isMyMessage: Boolean,
    /** Epoch millis — plain data only on the wire; rendered in the client's timezone. */
    val timestampEpochMillis: Long,
    val type: ChatMessage.ChatMessageType
)

fun ChatMessageDto.toChatMessage(): ChatMessage {
    return ChatMessage(
        id = id,
        content = content,
        author = author,
        isMyMessage = isMyMessage,
        timestamp = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(timestampEpochMillis), ZoneId.systemDefault()),
        type = type
    )
}

fun ChatMessage.toChatMessageDto(): ChatMessageDto {
    return ChatMessageDto(
        id = id,
        content = content,
        author = author,
        isMyMessage = isMyMessage,
        timestampEpochMillis = timestamp.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        type = type
    )
}