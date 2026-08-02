package com.transtrend.ai.assistant.context

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project

/**
 * Assembles the project-context block sent to the model, under a hard character budget.
 *
 * M1 scope: contents of the currently open files only. Reads documents (not VFS bytes) so
 * unsaved editor changes are included. Later milestones add the project file tree and
 * `@`-referenced files, which take budget priority.
 */
@Service(Service.Level.PROJECT)
class ProjectContextCollector(private val project: Project) {

    companion object {
        const val DEFAULT_BUDGET_CHARS = 24_000

        fun getInstance(project: Project): ProjectContextCollector =
            project.getService(ProjectContextCollector::class.java)
    }

    fun collect(budgetChars: Int = DEFAULT_BUDGET_CHARS): String = runReadAction {
        val documentManager = FileDocumentManager.getInstance()
        val block = StringBuilder()
        for (file in FileEditorManager.getInstance(project).openFiles) {
            if (block.length >= budgetChars) break
            if (file.fileType.isBinary) continue
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
}
