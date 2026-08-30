package com.transtrend.ai.assistant.context

import com.transtrend.ai.assistant.ContextFileDto
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
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

    fun collect(
        mentionPaths: List<String> = emptyList(),
        budgetChars: Int = DEFAULT_BUDGET_CHARS,
    ): String = runReadAction {
        val documentManager = FileDocumentManager.getInstance()
        val block = StringBuilder()
        val includedPaths = mutableSetOf<String>()

        // @-mentioned files first: they take priority in the budget.
        val fileSystem = LocalFileSystem.getInstance()
        for (path in mentionPaths) {
            if (block.length >= budgetChars) break
            val file = fileSystem.findFileByPath(path)
            when {
                file == null || file.isDirectory ->
                    block.append("// File: ").append(path).append(" [not found]\n\n")
                file.fileType.isBinary ->
                    block.append("// File: ").append(path).append(" [binary file omitted]\n\n")
                else -> {
                    includedPaths += file.path
                    appendFile(block, file.path + " [mentioned]", documentManager.getDocument(file)?.text, budgetChars)
                }
            }
        }

        for (file in openTextFiles()) {
            if (block.length >= budgetChars) break
            if (file.path in includedPaths) continue
            appendFile(block, file.path, documentManager.getDocument(file)?.text, budgetChars)
        }
        block.toString()
    }

    private fun appendFile(block: StringBuilder, header: String, text: String?, budgetChars: Int) {
        if (text == null) return
        block.append("// File: ").append(header).append('\n')
        val remaining = budgetChars - block.length
        if (text.length <= remaining) {
            block.append(text)
        } else {
            block.append(text, 0, remaining.coerceAtLeast(0)).append("\n// [truncated]")
        }
        block.append("\n\n")
    }

    private fun refreshContextFiles() {
        _contextFiles.value = openTextFiles().map { ContextFileDto(path = it.path, fileName = it.name) }
    }

    private fun openTextFiles(): List<VirtualFile> =
        FileEditorManager.getInstance(project).openFiles.filter { !it.fileType.isBinary }

    override fun dispose() {}
}
