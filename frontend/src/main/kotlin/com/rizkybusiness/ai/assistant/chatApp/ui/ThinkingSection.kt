package com.rizkybusiness.ai.assistant.chatApp.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.rizkybusiness.ai.assistant.ModularPluginFrontendBundle
import com.rizkybusiness.ai.assistant.chatApp.ui.markdown.MarkdownContent
import com.rizkybusiness.ai.assistant.chatApp.ui.utils.ChatAppColors
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JPanel

/**
 * Collapsible reasoning stream inside an assistant bubble. Expanded while the model is
 * still thinking (live "it's working" signal), auto-collapses to a toggle line once the
 * actual answer starts — unless the user has pinned it open/closed themselves.
 */
class ThinkingSection : JPanel() {

    private val header = JBLabel().apply {
        font = JBFont.small()
        foreground = ChatAppColors.Text.timestamp
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }
    private val body = MarkdownContent()

    private var expanded = true
    private var userPinned = false
    private var currentThinking = ""

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        border = JBUI.Borders.emptyBottom(4)
        header.alignmentX = LEFT_ALIGNMENT
        add(header)
        add(body)
        isVisible = false
        header.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                userPinned = true
                expanded = !expanded
                applyState(answerStarted = !expanded)
            }
        })
    }

    fun setThinking(thinking: String, answerStarted: Boolean) {
        if (thinking.isBlank()) {
            isVisible = false
            return
        }
        isVisible = true
        if (thinking != currentThinking) {
            currentThinking = thinking
            body.setMarkdown(thinking)
        }
        if (!userPinned) {
            expanded = !answerStarted
        }
        applyState(answerStarted)
        revalidate()
        repaint()
    }

    private fun applyState(answerStarted: Boolean) {
        body.isVisible = expanded
        header.icon = if (expanded) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
        header.text = ModularPluginFrontendBundle.message(
            if (answerStarted) "chat.thinking.done" else "chat.thinking.active"
        )
        revalidate()
        repaint()
    }

    fun setWrapWidth(px: Int) {
        body.setWrapWidth(px)
    }
}
