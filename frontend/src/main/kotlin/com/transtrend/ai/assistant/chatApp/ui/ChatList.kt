package com.transtrend.ai.assistant.chatApp.ui

import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.transtrend.ai.assistant.ChatMessage
import com.transtrend.ai.assistant.ModularPluginFrontendBundle
import com.transtrend.ai.assistant.chatApp.ui.utils.ChatAppColors
import com.transtrend.ai.assistant.chatApp.ui.utils.ChatUIConstants
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.Box
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane

class ChatList : JPanel() {
    private val messagesContainer: JPanel
    private val scrollPane: JScrollPane
    private val emptyPlaceholder: JPanel
    private val cardPanel: JPanel

    private val messageBubbles = mutableMapOf<String, MessageBubble>()
    private var availableWidth = 0

    companion object {
        private const val CARD_EMPTY = "empty"
        private const val CARD_MESSAGES = "messages"
    }

    init {
        setupAppearance()

        messagesContainer = createMessagesContainer()
        scrollPane = createScrollPane()
        emptyPlaceholder = createEmptyPlaceholder()

        cardPanel = JPanel(CardLayout()).apply {
            add(emptyPlaceholder, CARD_EMPTY)
            add(scrollPane, CARD_MESSAGES)
        }

        add(cardPanel, BorderLayout.CENTER)

        // Bubbles wrap to the visible width — a narrow tool window must never hide them.
        scrollPane.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = applyAvailableWidth()
        })
    }

    private fun applyAvailableWidth() {
        val width = scrollPane.viewport.width
        if (width <= 0 || width == availableWidth) return
        availableWidth = width
        messageBubbles.values.forEach { it.updateAvailableWidth(width) }
        refresh()
    }

    private fun setupAppearance() {
        layout = BorderLayout()
        background = ChatAppColors.Panel.background
    }

    private fun createMessagesContainer() = JPanel().apply {
        layout = GridBagLayout()
        background = ChatAppColors.Panel.background
        isOpaque = true
    }

    private fun createScrollPane() = JBScrollPane(messagesContainer).apply {
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        border = null
        isVisible = false
        viewport.isOpaque = true
        viewport.background = ChatAppColors.Panel.background
    }

    private fun createEmptyPlaceholder() = JPanel(GridBagLayout()).apply {
        background = ChatAppColors.Panel.background
        add(
            JLabel(ModularPluginFrontendBundle.message("chat.start.conversation")).apply {
                foreground = ChatAppColors.Text.disabled
                font = font.deriveFont(16f)
            }
        )
        isVisible = true
    }

    fun setMessages(messages: List<ChatMessage>) {
        if (messages.isEmpty()) {
            clearMessages()
            return
        }

        if (messageBubbles.isEmpty()) {
            showMessagesPanel()
        }

        removeDeletedMessages(messages)
        removeSpaceFillerIfPresent()

        addNewMessages(messages)

        refresh()
        scrollToBottom()
    }

    fun updateSearchHighlights(searchState: SearchState) {
        val resultIds = searchState.searchResultIds
        val currentId = searchState.currentSelectedSearchResultId

        messageBubbles.forEach { (messageId, bubble) ->
            val isMatching = resultIds.contains(messageId)
            val isHighlighted = messageId == currentId

            bubble.updateSearchState(isMatching, isHighlighted)
        }
    }


    private fun addNewMessages(messages: List<ChatMessage>) {
        val gbc = baseConstraints()

        messages.forEachIndexed { index, message ->
            val existingBubble = messageBubbles[message.id]
            if (existingBubble == null) {
                val bubble = MessageBubble(message)
                if (availableWidth > 0) bubble.updateAvailableWidth(availableWidth)
                messageBubbles[message.id] = bubble

                gbc.gridy = index
                gbc.anchor = if (message.isMyMessage) GridBagConstraints.EAST else GridBagConstraints.WEST

                messagesContainer.add(bubble, gbc)
            } else {
                existingBubble.updateFrom(message)
            }
        }

        fillRemainingSpace(messages.size, gbc)
    }

    private fun removeDeletedMessages(messages: List<ChatMessage>) {
        val currentIds = messages.map { it.id }.toSet()
        messageBubbles.keys
            .filter { it !in currentIds }
            .forEach { id ->
                messageBubbles.remove(id)?.let { messagesContainer.remove(it) }
            }
    }

    private fun showEmptyPanel() {
        (cardPanel.layout as CardLayout).show(cardPanel, CARD_EMPTY)
    }

    private fun showMessagesPanel() {
        (cardPanel.layout as CardLayout).show(cardPanel, CARD_MESSAGES)
    }

    private fun baseConstraints() = GridBagConstraints().apply {
        gridx = 0
        weightx = 1.0
        weighty = 0.0
        fill = GridBagConstraints.NONE
        insets = JBUI.insets(ChatUIConstants.Spacing.TINY)
    }

    private fun fillRemainingSpace(row: Int, gbc: GridBagConstraints) {
        gbc.gridy = row
        gbc.weighty = 1.0
        gbc.anchor = GridBagConstraints.NORTHWEST
        messagesContainer.add(Box.createVerticalGlue(), gbc)
    }

    private fun removeSpaceFillerIfPresent() {
        if (messagesContainer.componentCount > messageBubbles.size) {
            messagesContainer.remove(messagesContainer.componentCount - 1)
        }
    }

    private fun scrollToBottom() {
        val rect = Rectangle(0, messagesContainer.height - 1, 1, messagesContainer.height)
        messagesContainer.scrollRectToVisible(rect)
    }

    fun scrollToMessage(messageId: String) {
        val bubble = messageBubbles[messageId]
        bubble?.scrollRectToVisible(bubble.bounds)
    }

    private fun clearMessages() {
        messagesContainer.removeAll()
        messageBubbles.clear()
        showEmptyPanel()
        refresh()
    }

    private fun refresh() {
        messagesContainer.revalidate()
        messagesContainer.repaint()
    }
}