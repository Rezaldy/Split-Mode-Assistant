package com.transtrend.ai.assistant.chatApp.ui

import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.transtrend.ai.assistant.ModularPluginFrontendBundle
import com.transtrend.ai.assistant.chatApp.ui.utils.ChatAppColors
import java.awt.BorderLayout
import javax.swing.BorderFactory
import javax.swing.JPanel

/**
 * Prominent banner above the chat when the model source is unusable. The full backend
 * error message is shown in plain sight — never only in a tooltip or a log.
 */
class ModelsErrorBanner : JPanel() {

    private val label = JBLabel().apply {
        font = JBFont.small()
    }

    init {
        layout = BorderLayout()
        isOpaque = true
        background = ChatAppColors.MessageBubble.errorBackground
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 1, 0, ChatAppColors.MessageBubble.errorBorder),
            JBUI.Borders.empty(6, 12),
        )
        add(label, BorderLayout.CENTER)
        isVisible = false
    }

    fun setError(error: String?) {
        if (error.isNullOrBlank()) {
            isVisible = false
        } else {
            label.text = "<html>" + StringUtil.escapeXmlEntities(
                ModularPluginFrontendBundle.message("chat.models.error.banner", error)
            ) + "</html>"
            isVisible = true
        }
        revalidate()
        repaint()
    }
}
