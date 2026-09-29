package com.rizkybusiness.ai.assistant.chatApp.edit

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.platform.project.projectId
import com.intellij.util.ui.JBUI
import com.rizkybusiness.ai.assistant.CodeEditApi
import com.rizkybusiness.ai.assistant.CoroutineScopeHolder
import com.rizkybusiness.ai.assistant.EditPreviewDto
import com.rizkybusiness.ai.assistant.ModularPluginFrontendBundle.message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.swing.JComponent

/**
 * Apply flow for a code block: the backend computes the proposed text, the diff is shown
 * here from plain strings (no VFS on the frontend), and only an accepted diff is written.
 */
class CodeBlockApplier(private val project: Project) {

    /** [onStatus] runs on the EDT with a localized line and whether it is an error; not called on cancel. */
    fun apply(path: String, block: String, onStatus: (text: String, isError: Boolean) -> Unit) {
        CoroutineScopeHolder.getInstance(project).getPluginScope().launch {
            val status = try {
                run(path, block)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                thisLogger().warn("Code edit failed: stage=rpc path=$path", e)
                message("chat.edit.failed", e.message ?: e.javaClass.simpleName) to true
            }
            if (status != null) withContext(Dispatchers.EDT) { onStatus(status.first, status.second) }
        }
    }

    private suspend fun run(path: String, block: String): Pair<String, Boolean>? {
        val api = CodeEditApi.getInstance()
        val preview = api.preview(project.projectId(), path, block)
        preview.error?.let { return it to true }
        val accepted = withContext(Dispatchers.EDT) { ApplyDiffDialog(project, preview).showAndGet() }
        if (!accepted) return null
        val result = api.apply(project.projectId(), preview.path, preview.oldText, preview.newText)
        return (result.error ?: message("chat.edit.applied", preview.path)) to (result.error != null)
    }
}

private class ApplyDiffDialog(project: Project, preview: EditPreviewDto) : DialogWrapper(project) {
    private val panel = DiffManager.getInstance().createRequestPanel(project, disposable, null).apply {
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(preview.path.substringAfterLast('/'))
        val contents = DiffContentFactory.getInstance()
        val before = preview.oldText?.let { contents.create(project, it, fileType) } ?: contents.createEmpty()
        setRequest(
            SimpleDiffRequest(
                preview.path,
                before,
                contents.create(project, preview.newText, fileType),
                message(if (preview.oldText == null) "chat.edit.diff.new" else "chat.edit.diff.current"),
                message("chat.edit.diff.proposed"),
            ),
        )
    }

    init {
        title = message("chat.edit.diff.title", preview.path)
        setOKButtonText(message("chat.edit.diff.ok"))
        init()
    }

    override fun createCenterPanel(): JComponent = panel.component.apply { preferredSize = JBUI.size(900, 600) }
}
