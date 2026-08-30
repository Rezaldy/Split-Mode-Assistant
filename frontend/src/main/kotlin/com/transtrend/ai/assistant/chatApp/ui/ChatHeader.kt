package com.transtrend.ai.assistant.chatApp.ui

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.transtrend.ai.assistant.ModelsStateDto
import com.transtrend.ai.assistant.ModularPluginFrontendBundle
import com.transtrend.ai.assistant.chatApp.ui.utils.ButtonUtils
import com.transtrend.ai.assistant.chatApp.ui.utils.ChatAppColors
import com.transtrend.ai.assistant.chatApp.ui.utils.ChatAppIcons
import com.transtrend.ai.assistant.chatApp.ui.utils.ChatUIConstants
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ItemEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.border.CompoundBorder

class ChatHeader(
    private val onToggleSearch: (Boolean) -> Unit,
    private val onModelSelected: (String) -> Unit,
) : JPanel() {
    private var searchVisible = false
    private val modelCombo = ComboBox<String>()

    /** Guards against the item listener firing during programmatic updates. */
    private var updatingCombo = false

    init {
        setupAppearance()
        setupModelCombo()
        add(createTitleLabel(), BorderLayout.CENTER)
        add(createEastPanel(), BorderLayout.EAST)
    }

    private fun setupAppearance() {
        background = ChatAppColors.Panel.background
        layout = BorderLayout()
        border = CompoundBorder(
            JBUI.Borders.customLine(
                JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(),
                0, 0, ChatUIConstants.Input.BORDER_THICKNESS, 0
            ),
            JBUI.Borders.empty(
                ChatUIConstants.Spacing.LARGE,
                ChatUIConstants.Spacing.XLARGE
            )
        )
    }

    private fun setupModelCombo() {
        modelCombo.preferredSize = Dimension(JBUI.scale(180), modelCombo.preferredSize.height)
        modelCombo.isEnabled = false
        modelCombo.toolTipText = ModularPluginFrontendBundle.message("chat.models.tooltip")
        modelCombo.addItemListener { event ->
            if (!updatingCombo && event.stateChange == ItemEvent.SELECTED) {
                (event.item as? String)?.let(onModelSelected)
            }
        }
    }

    private fun createTitleLabel() = JBLabel(ModularPluginFrontendBundle.message("chat.header.title")).apply {
        font = JBFont.h3().asBold()
    }

    private fun createEastPanel() = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        add(modelCombo)
        add(Box.createHorizontalStrut(JBUI.scale(ChatUIConstants.Spacing.MEDIUM)))
        add(createSearchButton())
    }

    private fun createSearchButton() = ButtonUtils.createActionButton(
        icon = ChatAppIcons.Header.search,
        tooltip = ModularPluginFrontendBundle.message("chat.search.messages.button"),
        size = ChatUIConstants.Button.LARGE_ACTION_BUTTON_SIZE
    ) {
        searchVisible = !searchVisible
        onToggleSearch(searchVisible)
    }

    fun updateModels(state: ModelsStateDto) {
        updatingCombo = true
        try {
            modelCombo.removeAllItems()
            if (state.models.isEmpty()) {
                modelCombo.addItem(ModularPluginFrontendBundle.message("chat.models.unavailable"))
                modelCombo.isEnabled = false
                modelCombo.toolTipText =
                    state.error ?: ModularPluginFrontendBundle.message("chat.models.tooltip")
            } else {
                state.models.forEach(modelCombo::addItem)
                modelCombo.selectedItem = state.selectedModel
                // Env override pins the model; the dropdown then only displays it.
                modelCombo.isEnabled = !state.envOverride
                modelCombo.toolTipText = if (state.envOverride) {
                    ModularPluginFrontendBundle.message("chat.models.env.override")
                } else {
                    ModularPluginFrontendBundle.message("chat.models.tooltip")
                }
            }
        } finally {
            updatingCombo = false
        }
        revalidate()
        repaint()
    }
}
