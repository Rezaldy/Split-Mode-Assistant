package com.rizkybusiness.ai.assistant.chatApp.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.rizkybusiness.ai.assistant.FileRefDto
import com.rizkybusiness.ai.assistant.ModularPluginFrontendBundle
import com.rizkybusiness.ai.assistant.chatApp.ui.utils.ButtonUtils
import com.rizkybusiness.ai.assistant.chatApp.ui.utils.ChatAppColors
import com.rizkybusiness.ai.assistant.chatApp.ui.utils.ChatAppIcons
import com.rizkybusiness.ai.assistant.chatApp.ui.utils.ChatUIConstants
import com.rizkybusiness.ai.assistant.chatApp.viewmodel.MessageInputState
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.KeyStroke
import javax.swing.border.CompoundBorder
import javax.swing.border.LineBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class PromptInput(
    private val onInputChanged: (String) -> Unit,
    private val onSend: (String) -> Unit,
    private val onStop: (String) -> Unit,
    private val onMentionQuery: (String?) -> Unit = {}
) : JPanel() {

    private val textArea: JBTextArea
    private val scrollPane: JBScrollPane
    private val sendButton: JButton

    private var currentState: MessageInputState = MessageInputState.Enabled("")
    private var skipInputChangeUpdate = false

    /** Mention token ("@fileName") → full path; pruned against the text on send. */
    private val mentions = mutableMapOf<String, String>()
    private var mentionPopup: JBPopup? = null

    init {
        setupAppearance()

        textArea = createTextArea()
        scrollPane = createScrollPane(textArea)
        sendButton = createSendButton()

        add(scrollPane, BorderLayout.CENTER)
        add(sendButton, BorderLayout.EAST)

        setupKeyBindings()
    }

    private fun setupAppearance() {
        layout = BorderLayout(ChatUIConstants.Spacing.MEDIUM, 0)
        border = CompoundBorder(
            LineBorder(ChatAppColors.Prompt.border, ChatUIConstants.Input.BORDER_THICKNESS, true),
            JBUI.Borders.empty(ChatUIConstants.Spacing.MEDIUM, ChatUIConstants.Spacing.NORMAL)
        )
        background = ChatAppColors.Panel.background
    }

    private fun createTextArea() = JBTextArea().apply {
        lineWrap = true
        wrapStyleWord = true
        rows = 1
        border = JBUI.Borders.empty(
            ChatUIConstants.Input.TEXT_AREA_PAD_VERTICAL,
            ChatUIConstants.Input.TEXT_AREA_PAD_HORIZONTAL
        )

        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = handleTextChange()
            override fun removeUpdate(e: DocumentEvent?) = handleTextChange()
            override fun changedUpdate(e: DocumentEvent?) = handleTextChange()
        })
    }

    private fun createScrollPane(content: JComponent) = object : JBScrollPane(content) {
        override fun getPreferredSize(): Dimension {
            val base = super.getPreferredSize()
            val capped = base.height.coerceIn(
                ChatUIConstants.Input.MIN_HEIGHT,
                ChatUIConstants.Input.MAX_HEIGHT
            )
            return Dimension(base.width, capped)
        }
    }.apply {
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        border = LineBorder(
            JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(),
            ChatUIConstants.Input.BORDER_THICKNESS
        )
        minimumSize = Dimension(ChatUIConstants.Input.MIN_WIDTH, ChatUIConstants.Input.MIN_HEIGHT)
    }

    private fun createSendButton() = JButton().apply {
        icon = ChatAppIcons.Prompt.send
        isEnabled = false
        preferredSize = ChatUIConstants.Button.SEND_BUTTON_SIZE
        toolTipText = ModularPluginFrontendBundle.message("chat.prompt.send.tooltip")
        isFocusable = false
        isBorderPainted = false
        isContentAreaFilled = false
        addActionListener { handleButtonClick() }
        ButtonUtils.applyHoverEffect(this)
    }

    private fun handleTextChange() {
        if (skipInputChangeUpdate) {
            skipInputChangeUpdate = false
            return
        }

        val text = textArea.text
        onInputChanged(text)
        onMentionQuery(currentMentionQuery())

        sendButton.isEnabled = currentState != MessageInputState.Disabled && text.isNotBlank()
    }

    /**
     * The "@query" token being typed at the caret, or null when the caret isn't inside one.
     * A mention token starts at an "@" preceded by start-of-text or whitespace.
     */
    private fun currentMentionQuery(): String? {
        val caret = textArea.caretPosition.coerceAtMost(textArea.text.length)
        val upToCaret = textArea.text.substring(0, caret)
        val at = upToCaret.lastIndexOf('@')
        if (at < 0) return null
        if (at > 0 && !upToCaret[at - 1].isWhitespace()) return null
        val query = upToCaret.substring(at + 1)
        if (query.isEmpty() || query.length > 60 || query.any { it.isWhitespace() }) return null
        return query
    }

    fun showMentionResults(results: List<FileRefDto>) {
        hideMentionPopup()
        if (results.isEmpty() || currentMentionQuery() == null) return
        mentionPopup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(results)
            .setRenderer(SimpleListCellRenderer.create { label, value, _ -> label.text = value.presentablePath })
            .setRequestFocus(false)
            .setItemChosenCallback { insertMention(it) }
            .createPopup()
            .also { it.show(RelativePoint.getNorthWestOf(this)) }
    }

    private fun insertMention(ref: FileRefDto) {
        val caret = textArea.caretPosition.coerceAtMost(textArea.text.length)
        val at = textArea.text.substring(0, caret).lastIndexOf('@')
        if (at < 0) return
        val token = "@${ref.fileName}"
        mentions[token] = ref.path
        textArea.replaceRange("$token ", at, caret)
        hideMentionPopup()
        textArea.requestFocusInWindow()
    }

    private fun hideMentionPopup() {
        mentionPopup?.cancel()
        mentionPopup = null
    }

    /** Paths of mentions still present in the text; called at send time. */
    fun currentMentionPaths(): List<String> {
        val text = textArea.text
        mentions.keys.retainAll { it in text }
        return mentions.values.distinct().toList()
    }

    private fun setupKeyBindings() {
        val inputMap = textArea.getInputMap(JComponent.WHEN_FOCUSED)
        val actionMap = textArea.actionMap

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "send")
        actionMap.put("send", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                val text = textArea.text.trim()
                if (text.isEmpty()) return
                when (currentState) {
                    is MessageInputState.Sending -> onStop(text)
                    else -> handleSend()
                }
            }
        })

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "newline")
        actionMap.put("newline", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                skipInputChangeUpdate = true
                textArea.insert("\n", textArea.caretPosition)
            }
        })
    }

    fun updateState(state: MessageInputState) {
        currentState = state

        when (state) {
            MessageInputState.Disabled -> applySendButtonStyle(SendButtonStyle.Idle, textAreaEnabled = true, forceEnabled = false)
            is MessageInputState.Enabled,
            is MessageInputState.Sent,
            is MessageInputState.SendFailed -> applySendButtonStyle(SendButtonStyle.Ready, textAreaEnabled = true, forceEnabled = false)
            is MessageInputState.Sending -> applySendButtonStyle(SendButtonStyle.Stop, textAreaEnabled = false, forceEnabled = true)
        }
    }

    private fun applySendButtonStyle(style: SendButtonStyle, textAreaEnabled: Boolean, forceEnabled: Boolean) {
        val hasText = textArea.text.isNotBlank()
        sendButton.icon = style.iconFor(hasText)
        sendButton.toolTipText = ModularPluginFrontendBundle.message(style.tooltipKey)
        sendButton.isEnabled = forceEnabled || (style.allowsSend && hasText)
        textArea.isEnabled = textAreaEnabled
    }

    private fun handleButtonClick() {
        when (currentState) {
            is MessageInputState.Sending -> handleStop()
            else -> handleSend()
        }
    }

    private fun handleSend() {
        val text = textArea.text.trim()
        if (text.isEmpty()) return

        hideMentionPopup()
        onSend(text)
        skipInputChangeUpdate = true
        textArea.text = ""
        mentions.clear()
    }

    private fun handleStop() {
        onStop(textArea.text.trim())
    }

    private enum class SendButtonStyle(val tooltipKey: String, val allowsSend: Boolean) {
        Idle("chat.prompt.send.tooltip", allowsSend = false),
        Ready("chat.prompt.send.tooltip", allowsSend = true),
        Stop("chat.prompt.stop.tooltip", allowsSend = false);

        fun iconFor(hasText: Boolean): Icon = when (this) {
            Idle -> ChatAppIcons.Prompt.send
            Ready -> if (hasText) AllIcons.Actions.Execute else ChatAppIcons.Prompt.send
            Stop -> ChatAppIcons.Prompt.stop
        }
    }
}
