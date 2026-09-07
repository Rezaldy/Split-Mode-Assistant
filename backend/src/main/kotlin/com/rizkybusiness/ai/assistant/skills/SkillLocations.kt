package com.rizkybusiness.ai.assistant.skills

import com.intellij.openapi.project.Project
import java.nio.file.Path

/**
 * Where skills are looked up on the host, in precedence order within each scope.
 * `.agents/skills` is the cross-client convention; `.claude/skills` is scanned for
 * compatibility with skills installed for Claude Code. Uploads land in the plugin's own
 * user-level folder so nothing the plugin didn't create is ever deleted.
 */
object SkillLocations {
    const val NATIVE_DIR = ".code-assistant"
    private const val SKILLS_DIR = "skills"
    private val SCAN_DIRS = listOf(NATIVE_DIR, ".agents", ".claude")

    fun projectRoots(project: Project): List<Path> {
        val base = project.basePath ?: return emptyList()
        return SCAN_DIRS.map { Path.of(base, it, SKILLS_DIR) }
    }

    fun userRoots(): List<Path> =
        SCAN_DIRS.map { Path.of(System.getProperty("user.home"), it, SKILLS_DIR) }

    /** The only folder the plugin writes to. */
    fun uploadRoot(): Path = userRoots().first()
}
