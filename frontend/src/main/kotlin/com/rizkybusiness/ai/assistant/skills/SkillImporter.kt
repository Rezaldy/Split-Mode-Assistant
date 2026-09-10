package com.rizkybusiness.ai.assistant.skills

import com.rizkybusiness.ai.assistant.ModularPluginFrontendBundle
import com.rizkybusiness.ai.assistant.SkillFileDto
import com.rizkybusiness.ai.assistant.SkillUploadDto
import com.rizkybusiness.ai.assistant.SkillUploadResultDto
import com.rizkybusiness.ai.assistant.chatApp.viewmodel.FrontendSkillsModel
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.zip.ZipFile
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.filechooser.FileFilter
import kotlin.io.path.name

/**
 * Imports a skill folder or `.zip` from the machine the UI runs on into the host's user-level
 * skills folder.
 *
 * This is the one sanctioned filesystem access in the frontend module: the user picks a path on
 * their own machine, its bytes are read with `java.nio` and shipped over [SkillsApi] — no
 * project files, no VFS, no network. A plain Swing [JFileChooser] is used on purpose: in Remote
 * Development the IDE's `FileChooser` browses the *host* filesystem, which is the opposite of
 * what an upload needs.
 */
class SkillImporter(private val project: Project, private val scope: CoroutineScope) {

    companion object {
        /** Mirrors the backend caps so an oversize pick fails before anything crosses RPC. */
        const val MAX_FILE_BYTES = 512L * 1024
        const val MAX_TOTAL_BYTES = 2L * 1024 * 1024
        const val MANIFEST_FILE = "SKILL.md"
        private val SKIPPED_DIRS = setOf(".git", "node_modules", "__pycache__", "__MACOSX")
    }

    /** Local (pre-RPC) failures share the DTO shape so the caller has one result path. */
    private sealed interface Prepared {
        data class Ready(val upload: SkillUploadDto) : Prepared
        data class Rejected(val result: SkillUploadResultDto) : Prepared
    }

    /**
     * Shows the chooser (EDT), then reads + uploads off the EDT. Offers to overwrite when the host
     * reports an existing skill. [onResult] runs on the EDT; it is not called when the user cancels.
     */
    fun importInteractively(anchor: JComponent, onResult: (SkillUploadResultDto) -> Unit) {
        val chooser = JFileChooser().apply {
            dialogTitle = ModularPluginFrontendBundle.message("chat.skills.import.title")
            fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
            isAcceptAllFileFilterUsed = false
            fileFilter = object : FileFilter() {
                override fun accept(f: File) = f.isDirectory || f.name.endsWith(".zip", ignoreCase = true)
                override fun getDescription() = ModularPluginFrontendBundle.message("chat.skills.import.filter")
            }
        }
        if (chooser.showOpenDialog(anchor) != JFileChooser.APPROVE_OPTION) return
        val selected = chooser.selectedFile.toPath()

        scope.launch {
            val first = runImport(selected, overwrite = false)
            val final = if (first.code == "exists") {
                val answer = withContext(Dispatchers.EDT) {
                    Messages.showYesNoDialog(
                        project,
                        ModularPluginFrontendBundle.message("chat.skills.import.overwrite", first.skillName ?: selected.name),
                        ModularPluginFrontendBundle.message("chat.skills.import.title"),
                        Messages.getQuestionIcon(),
                    )
                }
                if (answer != Messages.YES) return@launch
                runImport(selected, overwrite = true)
            } else {
                first
            }
            withContext(Dispatchers.EDT) { onResult(final) }
        }
    }

    private suspend fun runImport(path: Path, overwrite: Boolean): SkillUploadResultDto {
        val prepared = try {
            withContext(Dispatchers.IO) { prepare(path, overwrite) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            thisLogger().warn("Skill import failed: stage=read path=$path", e)
            return failure("error", ModularPluginFrontendBundle.message("chat.skills.import.failed", e.message ?: e.javaClass.simpleName))
        }
        return when (prepared) {
            is Prepared.Rejected -> prepared.result
            is Prepared.Ready -> try {
                FrontendSkillsModel.getInstance(project).upload(prepared.upload)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                thisLogger().warn("Skill import failed: stage=upload name=${prepared.upload.name}", e)
                failure("error", ModularPluginFrontendBundle.message("chat.skills.import.failed", e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun prepare(path: Path, overwrite: Boolean): Prepared = when {
        Files.isDirectory(path) -> prepareFolder(path, overwrite)
        path.name.endsWith(".zip", ignoreCase = true) -> prepareZip(path, overwrite)
        else -> Prepared.Rejected(failure("invalid", ModularPluginFrontendBundle.message("chat.skills.import.not.skill")))
    }

    private fun prepareFolder(dir: Path, overwrite: Boolean): Prepared {
        if (!Files.isRegularFile(dir.resolve(MANIFEST_FILE))) {
            return Prepared.Rejected(failure("invalid", ModularPluginFrontendBundle.message("chat.skills.import.no.manifest")))
        }
        val files = ArrayList<SkillFileDto>()
        var total = 0L
        Files.walk(dir).use { stream ->
            for (file in stream) {
                if (file == dir) continue
                val relative = dir.relativize(file)
                if (relative.any { it.name in SKIPPED_DIRS || (it.name.startsWith(".") && Files.isDirectory(dir.resolve(it))) }) continue
                if (!Files.isRegularFile(file)) continue
                val size = Files.size(file)
                if (size > MAX_FILE_BYTES) return Prepared.Rejected(tooLarge())
                total += size
                if (total > MAX_TOTAL_BYTES) return Prepared.Rejected(tooLarge())
                files += SkillFileDto(
                    relativePath = relative.joinToString("/") { it.name },
                    contentBase64 = Base64.getEncoder().encodeToString(Files.readAllBytes(file)),
                )
            }
        }
        return Prepared.Ready(SkillUploadDto(name = dir.name, files = files, overwrite = overwrite))
    }

    /**
     * Accepts both `skill.zip` → `SKILL.md` at the root and the more common `skill.zip` →
     * `skill/SKILL.md` (one wrapping folder, which then names the skill).
     */
    private fun prepareZip(zipPath: Path, overwrite: Boolean): Prepared {
        ZipFile(zipPath.toFile()).use { zip ->
            val entries = zip.entries().asSequence()
                .filter { !it.isDirectory }
                .map { it to it.name.replace('\\', '/').trimStart('/') }
                .filter { (_, name) -> name.split('/').none { seg -> seg.isEmpty() || seg == "." || seg == ".." || seg in SKIPPED_DIRS } }
                .toList()
            val rootNames = entries.map { it.second }
            val prefix = when {
                MANIFEST_FILE in rootNames -> ""
                else -> rootNames.firstOrNull { it.endsWith("/$MANIFEST_FILE") && it.count { c -> c == '/' } == 1 }
                    ?.substringBefore('/')?.plus("/")
                    ?: return Prepared.Rejected(failure("invalid", ModularPluginFrontendBundle.message("chat.skills.import.no.manifest")))
            }
            val skillName = if (prefix.isEmpty()) zipPath.name.removeSuffix(".zip").removeSuffix(".ZIP") else prefix.trimEnd('/')
            val files = ArrayList<SkillFileDto>()
            var total = 0L
            for ((entry, name) in entries) {
                if (!name.startsWith(prefix)) continue
                val bytes = zip.getInputStream(entry).use { it.readNBytes((MAX_FILE_BYTES + 1).toInt()) }
                if (bytes.size > MAX_FILE_BYTES) return Prepared.Rejected(tooLarge())
                total += bytes.size
                if (total > MAX_TOTAL_BYTES) return Prepared.Rejected(tooLarge())
                files += SkillFileDto(
                    relativePath = name.removePrefix(prefix),
                    contentBase64 = Base64.getEncoder().encodeToString(bytes),
                )
            }
            return Prepared.Ready(SkillUploadDto(name = skillName, files = files, overwrite = overwrite))
        }
    }

    private fun tooLarge() = failure(
        "too-large",
        ModularPluginFrontendBundle.message("chat.skills.import.too.large", MAX_TOTAL_BYTES / 1024),
    )

    private fun failure(code: String, message: String) =
        SkillUploadResultDto(success = false, code = code, message = message)
}
