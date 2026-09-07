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

/** Project-index state for the chat UI's sync indicator. */
@Serializable
data class IndexStatusDto(
    val enabled: Boolean = false,
    /** "idle" | "building" | "ready" | "error" */
    val phase: String = "idle",
    /** Human-readable status line (localized on the backend). */
    val detail: String = "",
    /** True when the index lags the project: pending edits, never built, or errored. */
    val unsynced: Boolean = false,
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
    val type: ChatMessage.ChatMessageType,
    val thinking: String = "",
    /** Token usage for the producing request (assistant messages; 0 = unknown). */
    val promptTokens: Int = 0,
    val replyTokens: Int = 0,
    /** num_ctx the request was sent with; 0 = unknown. */
    val contextLimit: Int = 0,
)

fun ChatMessageDto.toChatMessage(): ChatMessage {
    return ChatMessage(
        id = id,
        content = content,
        author = author,
        isMyMessage = isMyMessage,
        timestamp = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(timestampEpochMillis), ZoneId.systemDefault()),
        type = type,
        thinking = thinking,
        promptTokens = promptTokens,
        replyTokens = replyTokens,
        contextLimit = contextLimit,
    )
}

fun ChatMessage.toChatMessageDto(): ChatMessageDto {
    return ChatMessageDto(
        id = id,
        content = content,
        author = author,
        isMyMessage = isMyMessage,
        timestampEpochMillis = timestamp.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        type = type,
        thinking = thinking,
        promptTokens = promptTokens,
        replyTokens = replyTokens,
        contextLimit = contextLimit,
    )
}
/** A skill discovered on the host. Catalog data only — the SKILL.md body is read on the host at activation time. */
@Serializable
data class SkillDto(
    val name: String,
    val description: String,
    /** "project" or "user". */
    val scope: String,
    /** Absolute host path of the SKILL.md file. */
    val location: String,
    val enabled: Boolean = true,
    /** True when it lives in the plugin's own user-level folder (uploaded there) and may be deleted from the UI. */
    val uploaded: Boolean = false,
    /** Localized, non-fatal findings: shadowed copy, name/folder mismatch, … */
    val warnings: List<String> = emptyList(),
)

@Serializable
data class SkillsStateDto(
    val skills: List<SkillDto> = emptyList(),
    /** Localized note shown above the list, e.g. project skills hidden until the project is trusted. */
    val error: String? = null,
)

/** One file of an uploaded skill. Base64 on purpose: kotlinx JSON encodes a ByteArray as a list of numbers. */
@Serializable
data class SkillFileDto(
    /** Path relative to the skill folder, `/`-separated (e.g. `SKILL.md`, `references/guide.md`). */
    val relativePath: String,
    val contentBase64: String,
)

@Serializable
data class SkillUploadDto(
    /** Folder name chosen on the client; the backend uses the manifest `name` for the target folder. */
    val name: String,
    val files: List<SkillFileDto>,
    val overwrite: Boolean = false,
)

@Serializable
data class SkillUploadResultDto(
    val success: Boolean,
    /** "ok" | "exists" | "invalid" | "too-large" | "error" — lets the client branch (offer overwrite) without parsing text. */
    val code: String,
    /** Localized on the backend. */
    val message: String,
    val skillName: String? = null,
)
