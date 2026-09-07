package com.rizkybusiness.ai.assistant.skills

/**
 * A parsed `SKILL.md` (https://agentskills.io/specification). Pure data — no platform types —
 * so the parser and store stay unit-testable.
 */
data class SkillManifest(
    val name: String,
    val description: String,
    val license: String? = null,
    val compatibility: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val allowedTools: List<String> = emptyList(),
    /** Markdown after the closing frontmatter delimiter, trimmed. */
    val body: String,
    val warnings: List<SkillWarning> = emptyList(),
)

/** Non-fatal finding. [code] maps to the bundle key `skills.warning.<code>`; [arg] is its {0}. */
data class SkillWarning(val code: String, val arg: String = "") {
    companion object {
        const val NAME_MISMATCH = "name.mismatch"
        const val NAME_TOO_LONG = "name.too.long"
        const val DESCRIPTION_TOO_LONG = "description.too.long"
        const val UNKNOWN_KEY = "unknown.key"
        /** Added by discovery, not the parser: another copy of the same name was ignored. */
        const val SHADOWED = "shadowed"
    }
}

sealed interface SkillParseResult {
    data class Ok(val manifest: SkillManifest) : SkillParseResult

    /** The skill is not loaded; [reason] is log-only English (missing description, no frontmatter …). */
    data class Skipped(val reason: String) : SkillParseResult
}

/** Outcome of [SkillStore] operations; localized into [com.rizkybusiness.ai.assistant.SkillUploadResultDto] by the API layer. */
sealed interface SkillStoreResult {
    data class Ok(val name: String) : SkillStoreResult
    data class Exists(val name: String) : SkillStoreResult

    /** [code] maps to `skills.upload.invalid.<code>`: `missing.manifest`, `bad.path`, `bad.manifest`. */
    data class Invalid(val code: String, val detail: String = "") : SkillStoreResult
    data class TooLarge(val limitKb: Long) : SkillStoreResult
    data class Failed(val message: String) : SkillStoreResult
    data class NotFound(val name: String) : SkillStoreResult
}
