package com.rizkybusiness.ai.assistant.skills

import com.rizkybusiness.ai.assistant.SkillUploadDto
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.Comparator
import java.util.UUID

/**
 * Writes and deletes skills under a single root folder on disk (the plugin's own user-level
 * skills folder — see [SkillLocations.uploadRoot]). Pure JDK on purpose: no platform imports,
 * so it is unit-testable without the IDE fixture.
 */
class SkillStore(private val root: Path) {

    companion object {
        const val MAX_FILE_BYTES = 512L * 1024
        const val MAX_TOTAL_BYTES = 2L * 1024 * 1024
        private val NAME_REGEX = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
    }

    fun write(upload: SkillUploadDto): SkillStoreResult {
        val manifestFile = upload.files.firstOrNull { it.relativePath == "SKILL.md" }
            ?: return SkillStoreResult.Invalid("missing.manifest")

        for (file in upload.files) {
            if (!isValidRelativePath(file.relativePath)) {
                return SkillStoreResult.Invalid("bad.path", file.relativePath)
            }
        }

        val decoded = LinkedHashMap<String, ByteArray>()
        var total = 0L
        for (file in upload.files) {
            val bytes = try {
                Base64.getDecoder().decode(file.contentBase64)
            } catch (e: IllegalArgumentException) {
                return SkillStoreResult.Failed("invalid base64 in ${file.relativePath}")
            }
            if (bytes.size > MAX_FILE_BYTES) return SkillStoreResult.TooLarge(MAX_TOTAL_BYTES / 1024)
            total += bytes.size
            if (total > MAX_TOTAL_BYTES) return SkillStoreResult.TooLarge(MAX_TOTAL_BYTES / 1024)
            decoded[file.relativePath] = bytes
        }

        val manifestText = String(decoded.getValue(manifestFile.relativePath), Charsets.UTF_8)
        val name = when (val parsed = SkillManifestParser.parse(manifestText, upload.name)) {
            is SkillParseResult.Ok -> parsed.manifest.name
            is SkillParseResult.Skipped -> return SkillStoreResult.Invalid("bad.manifest", parsed.reason)
        }

        val target = root.resolve(name)
        if (Files.exists(target) && !upload.overwrite) return SkillStoreResult.Exists(name)

        val temp = root.resolve(".tmp-$name-${UUID.randomUUID()}")
        return try {
            Files.createDirectories(temp)
            for ((relativePath, bytes) in decoded) {
                val dest = temp.resolve(relativePath)
                Files.createDirectories(dest.parent ?: temp)
                Files.write(dest, bytes)
            }
            if (Files.exists(target)) deleteRecursively(target)
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp, target)
            }
            SkillStoreResult.Ok(name)
        } catch (e: Exception) {
            runCatching { deleteRecursively(temp) }
            SkillStoreResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    fun delete(name: String): SkillStoreResult {
        if (!NAME_REGEX.matches(name)) return SkillStoreResult.Invalid("bad.path", name)
        val target = root.resolve(name).normalize()
        if (!target.startsWith(root.normalize()) || !Files.isDirectory(target)) {
            return SkillStoreResult.NotFound(name)
        }
        deleteRecursively(target)
        return SkillStoreResult.Ok(name)
    }

    private fun isValidRelativePath(relativePath: String): Boolean {
        if (relativePath.isBlank()) return false
        if (relativePath.contains('\\') || relativePath.contains(':')) return false
        if (Path.of(relativePath).isAbsolute) return false
        val segments = relativePath.split("/")
        return segments.all { it.isNotEmpty() && it != "." && it != ".." }
    }

    private fun deleteRecursively(dir: Path) {
        if (!Files.exists(dir)) return
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
        }
    }
}
