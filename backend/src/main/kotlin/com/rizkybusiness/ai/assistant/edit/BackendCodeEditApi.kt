package com.rizkybusiness.ai.assistant.edit

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.project.ProjectId
import com.intellij.platform.project.findProjectOrNull
import com.rizkybusiness.ai.assistant.CodeEditApi
import com.rizkybusiness.ai.assistant.EditPreviewDto
import com.rizkybusiness.ai.assistant.EditResultDto
import com.rizkybusiness.ai.assistant.ModularPluginBackendBundle.message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Model output is untrusted: every path is resolved against the project base dir and
 * rejected when it escapes it. Writes go through the Document inside one
 * WriteCommandAction so a single Ctrl+Z reverts an applied suggestion.
 */
class BackendCodeEditApi : CodeEditApi {

    override suspend fun preview(projectId: ProjectId, path: String, block: String): EditPreviewDto {
        val project = projectId.findProjectOrNull() ?: return EditPreviewDto(error = message("edit.error.no.project"))
        val target = resolve(project, path) ?: return EditPreviewDto(error = message("edit.error.outside.project", path))
        val relative = relativePath(project, target)
        val file = findFile(target)
        val oldText = if (file == null) null else {
            readText(file) ?: return EditPreviewDto(error = message("edit.error.unreadable", relative))
        }
        return when (val result = SearchReplaceApplier.apply(oldText, block)) {
            is SearchReplaceApplier.Result.Ok -> EditPreviewDto(relative, oldText, withTrailingNewline(oldText, result.newText))
            is SearchReplaceApplier.Result.Error -> EditPreviewDto(relative, error = message("edit.error.hunk", relative, result.message))
        }
    }

    override suspend fun apply(projectId: ProjectId, path: String, expectedOldText: String?, newText: String): EditResultDto {
        val project = projectId.findProjectOrNull() ?: return EditResultDto(message("edit.error.no.project"))
        val target = resolve(project, path) ?: return EditResultDto(message("edit.error.outside.project", path))
        val relative = relativePath(project, target)
        val existing = findFile(target)
        if ((existing == null) != (expectedOldText == null)) return EditResultDto(message("edit.error.stale", relative))
        return try {
            val file = withContext(Dispatchers.EDT) {
                WriteCommandAction.writeCommandAction(project)
                    .withName(message("edit.command.name"))
                    .compute<VirtualFile?, Exception> {
                        val file = existing ?: createFile(target)
                        val document = FileDocumentManager.getInstance().getDocument(file)
                        if (document == null || (expectedOldText != null && document.text != expectedOldText)) {
                            null
                        } else {
                            replaceChanged(document, newText)
                            file
                        }
                    }
            } ?: return EditResultDto(message("edit.error.stale", relative))
            withContext(Dispatchers.EDT) { FileEditorManager.getInstance(project).openFile(file, true) }
            thisLogger().info("Code edit applied: path=$relative created=${existing == null} chars=${newText.length}")
            EditResultDto()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            thisLogger().warn("Code edit failed: path=$relative", e)
            EditResultDto(message("edit.error.write", relative, e.message ?: e.javaClass.simpleName))
        }
    }

    /** Code fences drop the final newline; restore it for new files and when the old text had one. */
    private fun withTrailingNewline(oldText: String?, newText: String): String =
        if ((oldText == null || oldText.endsWith('\n')) && !newText.endsWith('\n')) newText + '\n' else newText

    private fun resolve(project: Project, path: String): Path? {
        val base = project.basePath?.let { Path.of(it).normalize() } ?: return null
        return try {
            val raw = Path.of(path.trim())
            val inside = { p: Path -> p.normalize().takeIf { it.startsWith(base) && it != base } }
            // A leading "/" from the model usually means project root, not filesystem root.
            (if (raw.isAbsolute) inside(raw) else null) ?: inside(base.resolve(path.trim().trimStart('/', '\\')))
        } catch (_: InvalidPathException) {
            null
        }
    }

    private fun relativePath(project: Project, target: Path): String =
        project.basePath?.let { Path.of(it).normalize().relativize(target).toString().replace('\\', '/') } ?: target.toString()

    private fun findFile(target: Path): VirtualFile? =
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)?.takeUnless { it.isDirectory }

    private suspend fun readText(file: VirtualFile): String? = readAction {
        if (file.fileType.isBinary) null else FileDocumentManager.getInstance().getDocument(file)?.text
    }

    private fun createFile(target: Path): VirtualFile {
        val dir = VfsUtil.createDirectoryIfMissing(target.parent.toString().replace('\\', '/'))
            ?: error("Cannot create directory ${target.parent}")
        return dir.createChildData(this, target.fileName.toString())
    }

    /** Replaces only the differing middle so carets, folds and markers outside it survive. */
    private fun replaceChanged(document: Document, newText: String) {
        val old = document.charsSequence
        val prefix = old.commonPrefixWith(newText).length
        val maxSuffix = minOf(old.length, newText.length) - prefix
        var suffix = 0
        while (suffix < maxSuffix && old[old.length - 1 - suffix] == newText[newText.length - 1 - suffix]) suffix++
        document.replaceString(prefix, old.length - suffix, newText.substring(prefix, newText.length - suffix))
    }
}
