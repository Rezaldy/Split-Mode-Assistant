package com.rizkybusiness.ai.assistant.skills

/**
 * Parses a `SKILL.md` per https://agentskills.io/specification. Pure Kotlin — no platform
 * imports, no YAML library — so [SkillDiscoveryService] and [SkillStore] stay unit-testable
 * and dependency-free. Deliberately lenient (per the integration guide): unknown or oversized
 * fields warn rather than fail; only a handful of structural problems are fatal.
 */
object SkillManifestParser {

    private val NAME_REGEX = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
    private val BLOCK_SCALAR_INDICATOR = Regex("^[|>][+-]?$")
    private const val MAX_NAME_LENGTH = 64
    private const val MAX_DESCRIPTION_LENGTH = 1024

    fun parse(text: String, expectedDirName: String?): SkillParseResult {
        val normalized = text.replace("\r\n", "\n").replace("\r", "\n").removePrefix("﻿")
        val lines = normalized.split("\n")

        val openIndex = lines.indexOfFirst { it.isNotBlank() }
        if (openIndex < 0 || lines[openIndex].trimEnd() != "---") {
            return SkillParseResult.Skipped("no YAML frontmatter")
        }
        val closeIndex = ((openIndex + 1) until lines.size).firstOrNull { i ->
            val trimmed = lines[i].trimEnd()
            trimmed == "---" || trimmed == "..."
        }
        if (closeIndex == null) {
            return SkillParseResult.Skipped("no YAML frontmatter")
        }

        val frontmatter = lines.subList(openIndex + 1, closeIndex)
        val body = lines.subList(closeIndex + 1, lines.size).joinToString("\n").trim()

        var name = ""
        var description = ""
        var license: String? = null
        var compatibility: String? = null
        var metadata: Map<String, String> = emptyMap()
        var allowedTools: List<String> = emptyList()
        val warnings = mutableListOf<SkillWarning>()

        fun assign(rawKey: String, key: String, value: String) {
            when (key) {
                "name" -> name = value
                "description" -> description = value
                "license" -> license = value.ifBlank { null }
                "compatibility" -> compatibility = value.ifBlank { null }
                "allowed-tools" -> allowedTools = value.split(Regex("\\s+")).filter { it.isNotBlank() }
                else -> warnings += SkillWarning(SkillWarning.UNKNOWN_KEY, rawKey)
            }
        }

        var i = 0
        while (i < frontmatter.size) {
            val rawLine = frontmatter[i]
            if (rawLine.isBlank()) {
                i++
                continue
            }
            val trimmedStart = rawLine.trimStart()
            if (trimmedStart.startsWith("#")) {
                i++
                continue
            }
            val indent = rawLine.length - trimmedStart.length
            if (indent > 0) {
                // Stray indented line outside of a recognized block/map continuation — ignore.
                i++
                continue
            }
            val colonIndex = rawLine.indexOf(':')
            if (colonIndex < 0) {
                i++
                continue
            }
            val rawKey = rawLine.substring(0, colonIndex).trim()
            val key = rawKey.lowercase()
            val rawValue = rawLine.substring(colonIndex + 1)
            val trimmedValue = rawValue.trim()

            when {
                trimmedValue.isEmpty() && key == "metadata" -> {
                    i++
                    val map = linkedMapOf<String, String>()
                    while (i < frontmatter.size) {
                        val line = frontmatter[i]
                        if (line.isBlank()) {
                            i++
                            continue
                        }
                        val ts = line.trimStart()
                        if (ts.startsWith("#")) {
                            i++
                            continue
                        }
                        if (line.length - ts.length == 0) break
                        val ci = ts.indexOf(':')
                        if (ci < 0) {
                            i++
                            continue
                        }
                        map[ts.substring(0, ci).trim()] = parsePlainValue(ts.substring(ci + 1))
                        i++
                    }
                    metadata = map
                }
                BLOCK_SCALAR_INDICATOR.matches(trimmedValue) -> {
                    val indicator = trimmedValue[0]
                    i++
                    val blockLines = mutableListOf<String>()
                    while (i < frontmatter.size) {
                        val line = frontmatter[i]
                        if (line.isBlank()) {
                            blockLines += ""
                            i++
                            continue
                        }
                        if (line.length - line.trimStart().length == 0) break
                        blockLines += line
                        i++
                    }
                    assign(rawKey, key, parseBlockScalar(indicator, blockLines))
                }
                else -> {
                    assign(rawKey, key, parsePlainValue(rawValue))
                    i++
                }
            }
        }

        if (name.isBlank()) return SkillParseResult.Skipped("missing name")
        if (!NAME_REGEX.matches(name)) return SkillParseResult.Skipped("invalid name '$name'")
        if (name.length > MAX_NAME_LENGTH) warnings += SkillWarning(SkillWarning.NAME_TOO_LONG)
        if (description.isBlank()) return SkillParseResult.Skipped("missing description")
        if (description.length > MAX_DESCRIPTION_LENGTH) warnings += SkillWarning(SkillWarning.DESCRIPTION_TOO_LONG)
        if (expectedDirName != null && expectedDirName != name) {
            warnings += SkillWarning(SkillWarning.NAME_MISMATCH, expectedDirName)
        }

        return SkillParseResult.Ok(
            SkillManifest(
                name = name,
                description = description,
                license = license,
                compatibility = compatibility,
                metadata = metadata,
                allowedTools = allowedTools,
                body = body,
                warnings = warnings,
            )
        )
    }

    private fun parsePlainValue(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
            return unescapeDoubleQuoted(trimmed.substring(1, trimmed.length - 1))
        }
        if (trimmed.length >= 2 && trimmed.first() == '\'' && trimmed.last() == '\'') {
            return trimmed.substring(1, trimmed.length - 1)
        }
        return trimmed
    }

    private fun unescapeDoubleQuoted(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length && (s[i + 1] == '"' || s[i + 1] == '\\')) {
                sb.append(s[i + 1])
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** [rawLines] hold the block's raw (still-indented) content lines; blank lines are already "". */
    private fun parseBlockScalar(indicator: Char, rawLines: List<String>): String {
        val minIndent = rawLines
            .filter { it.isNotEmpty() }
            .minOfOrNull { it.length - it.trimStart().length } ?: 0
        val stripped = rawLines.map { line ->
            when {
                line.isEmpty() -> ""
                line.length >= minIndent -> line.substring(minIndent)
                else -> line.trimStart()
            }
        }
        val joined = if (indicator == '|') stripped.joinToString("\n") else foldLines(stripped)
        return joined.trim()
    }

    /** Folds single newlines between non-blank lines into spaces; a blank line becomes `\n`. */
    private fun foldLines(lines: List<String>): String {
        val sb = StringBuilder()
        var previousWasBlank = true
        for (line in lines) {
            if (line.isEmpty()) {
                sb.append("\n")
                previousWasBlank = true
            } else {
                if (sb.isNotEmpty() && !previousWasBlank) sb.append(" ")
                sb.append(line)
                previousWasBlank = false
            }
        }
        return sb.toString()
    }
}
