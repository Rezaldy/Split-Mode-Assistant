package com.transtrend.ai.assistant.chatApp.ui

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.transtrend.ai.assistant.ContextFileDto
import com.transtrend.ai.assistant.ModularPluginFrontendBundle
import com.transtrend.ai.assistant.chatApp.ui.utils.ChatAppColors
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Thin strip between the message history and the prompt input showing which files the
 * backend currently feeds to the model as context. The list comes from the host over RPC —
 * the frontend never inspects editors or the filesystem itself.
 */
class ContextFilesBar : JPanel() {

    private val label = JBLabel().apply {
        font = JBFont.small()
        foreground = ChatAppColors.Text.timestamp
    }

    init {
        layout = BorderLayout()
        isOpaque = false
        border = JBUI.Borders.empty(2, 12)
        add(label, BorderLayout.CENTER)
        isVisible = false
    }

    fun setFiles(files: List<ContextFileDto>) {
        if (files.isEmpty()) {
            isVisible = false
        } else {
            label.text = ModularPluginFrontendBundle.message(
                "chat.context.files",
                files.joinToString(", ") { it.fileName },
            )
            // Full paths on hover; the label itself stays compact.
            label.toolTipText = "<html>" + files.joinToString("<br>") { it.path } + "</html>"
            isVisible = true
        }
        revalidate()
        repaint()
    }
}
