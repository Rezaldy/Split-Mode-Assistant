package com.transtrend.ai.assistant.context

import com.transtrend.ai.assistant.ContextFileDto
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Assembles the project-context block sent to the model, under a hard character budget,
 * and publishes which files that context is built from (shown in the chat UI).
 *
 * M1 scope: contents of the currently open files only. Reads documents (not VFS bytes) so
 * unsaved editor changes are included. Later milestones add the project file tree and
 * `@`-referenced files, which take budget priority.
 */
@Service(Service.Level.PROJECT)
class ProjectContextCollector(private val project: Project) : Disposable {

    companion object {
        const val DEFAULT_BUDGET_CHARS = 24_000

        fun getInstance(project: Project): ProjectContextCollector =
            project.getService(ProjectContextCollector::class.java)
    }

    private val _contextFiles = MutableStateFlow<List<ContextFileDto>>(emptyList())

    /** Files currently counted as context; updates when editors open or close on the host. */
    val contextFiles: StateFlow<List<ContextFileDto>> = _contextFiles.asStateFlow()

    init {
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) = refreshContextFiles()
                override fun fileClosed(source: FileEditorManager, file: VirtualFile) = refreshContextFiles()
            },
        )
        refreshContextFiles()
    }

    fun collect(budgetChars: Int = DEFAULT_BUDGET_CHARS): String = runReadAction {
        val documentManager = FileDocumentManager.getInstance()
        val block = StringBuilder()
        for (file in openTextFiles()) {
            if (block.length >= budgetChars) break
            val text = documentManager.getDocument(file)?.text ?: continue
            block.append("// File: ").append(file.path).append('\n')
            val remaining = budgetChars - block.length
            if (text.length <= remaining) {
                block.append(text)
            } else {
                block.append(text, 0, remaining.coerceAtLeast(0)).append("\n// [truncated]")
            }
            block.append("\n\n")
        }
        block.toString()
    }

    private fun refreshContextFiles() {
        _contextFiles.value = openTextFiles().map { ContextFileDto(path = it.path, fileName = it.name) }
    }

    private fun openTextFiles(): List<VirtualFile> =
        FileEditorManager.getInstance(project).openFiles.filter { !it.fileType.isBinary }

    override fun dispose() {}
}
