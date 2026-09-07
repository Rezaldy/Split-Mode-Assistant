package com.rizkybusiness.ai.assistant.settings

import com.rizkybusiness.ai.assistant.ModularPluginBackendBundle
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.JPanel

/** System prompts sent with every chat and commit-message request, sub-page of the root settings page. */
class PromptsConfigurable : Configurable {

    private var panel: JPanel? = null
    private val chatPromptArea = promptArea()
    private val commitPromptArea = promptArea()

    private fun promptArea() = JBTextArea(5, 60).apply {
        lineWrap = true
        wrapStyleWord = true
        font = JBFont.regular()
        margin = JBUI.insets(4)
    }

    private fun promptScrollPane(area: JBTextArea) = JBScrollPane(area).apply {
        preferredSize = JBUI.size(560, 96)
    }

    override fun getDisplayName(): String =
        ModularPluginBackendBundle.message("settings.prompts.display.name")

    override fun createComponent(): JComponent {
        val builder = FormBuilder.createFormBuilder()
            .addComponent(JBLabel(ModularPluginBackendBundle.message("settings.prompts.chat")))
            .addComponent(promptScrollPane(chatPromptArea))
            .addComponent(JBLabel(ModularPluginBackendBundle.message("settings.prompts.commit")))
            .addComponent(promptScrollPane(commitPromptArea))
            .addComponent(SettingsUi.hintLabel(ModularPluginBackendBundle.message("settings.prompts.hint")))

        return builder.addComponentFillVertically(JPanel(), 0).panel.also {
            panel = it
            reset()
        }
    }

    override fun isModified(): Boolean {
        val settings = AssistantSettings.getInstance()
        return chatPromptArea.text.trim() != settings.effectiveChatSystemPrompt ||
            commitPromptArea.text.trim() != settings.effectiveCommitSystemPrompt
    }

    override fun apply() {
        val settings = AssistantSettings.getInstance()
        settings.storedChatSystemPrompt = chatPromptArea.text
        settings.storedCommitSystemPrompt = commitPromptArea.text
        // Blanked areas fall back to the built-in defaults — reflect that immediately.
        chatPromptArea.text = settings.effectiveChatSystemPrompt
        commitPromptArea.text = settings.effectiveCommitSystemPrompt
    }

    override fun reset() {
        val settings = AssistantSettings.getInstance()
        chatPromptArea.text = settings.effectiveChatSystemPrompt
        commitPromptArea.text = settings.effectiveCommitSystemPrompt
    }

    override fun disposeUIResources() {
        panel = null
    }
}
