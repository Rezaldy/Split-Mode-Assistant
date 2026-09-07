package com.rizkybusiness.ai.assistant.settings

import com.rizkybusiness.ai.assistant.ModularPluginBackendBundle
import com.rizkybusiness.ai.assistant.models.BackendModelsService
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Registered from the backend module: in split mode this panel appears under
 * "Settings on Host", which is correct — the model-source connection lives on the host.
 * This is the root settings page ("Split Mode Assistant"); the Prompts and Indexing
 * sub-pages are registered as its children.
 */
class AssistantGeneralConfigurable : Configurable {

    private var panel: JPanel? = null
    private val baseUrlField = JBTextField(30)
    private val useProxyCheckbox = JBCheckBox(ModularPluginBackendBundle.message("settings.use.proxy"))
    private val contextTokensField = JBTextField(8)

    override fun getDisplayName(): String =
        ModularPluginBackendBundle.message("settings.display.name")

    override fun createComponent(): JComponent {
        val envUrl = System.getenv("OLLAMA_BASE_URL")?.takeIf { it.isNotBlank() }

        baseUrlField.emptyText.text = AssistantSettings.DEFAULT_BASE_URL
        baseUrlField.isEnabled = envUrl == null

        val builder = FormBuilder.createFormBuilder()
            .addLabeledComponent(ModularPluginBackendBundle.message("settings.base.url"), baseUrlField)
        if (envUrl != null) {
            builder.addComponentToRightColumn(SettingsUi.hintLabel(
                ModularPluginBackendBundle.message("settings.env.override.url", envUrl)))
        }
        builder.addComponentToRightColumn(SettingsUi.hintLabel(
            ModularPluginBackendBundle.message("settings.env.override.hint")))
        builder.addLabeledComponent(
            ModularPluginBackendBundle.message("settings.context.tokens"), contextTokensField)
            .addComponentToRightColumn(SettingsUi.hintLabel(
                ModularPluginBackendBundle.message("settings.context.tokens.hint")))
        builder.addComponent(useProxyCheckbox)
            .addComponentToRightColumn(SettingsUi.hintLabel(
                ModularPluginBackendBundle.message("settings.use.proxy.hint")))

        return builder.addComponentFillVertically(JPanel(), 0).panel.also {
            panel = it
            reset()
        }
    }

    override fun isModified(): Boolean {
        val settings = AssistantSettings.getInstance()
        return baseUrlField.text.trim().trimEnd('/') != settings.storedBaseUrl ||
            (contextTokensField.text.trim().toIntOrNull() ?: settings.contextTokens) != settings.contextTokens ||
            useProxyCheckbox.isSelected != settings.useProxy
    }

    override fun apply() {
        val settings = AssistantSettings.getInstance()
        settings.storedBaseUrl = baseUrlField.text
        contextTokensField.text.trim().toIntOrNull()?.let { settings.contextTokens = it }
        contextTokensField.text = settings.contextTokens.toString()
        settings.useProxy = useProxyCheckbox.isSelected
        BackendModelsService.getInstance().refreshAsync()
    }

    override fun reset() {
        val settings = AssistantSettings.getInstance()
        baseUrlField.text = settings.storedBaseUrl
        contextTokensField.text = settings.contextTokens.toString()
        useProxyCheckbox.isSelected = settings.useProxy
    }

    override fun disposeUIResources() {
        panel = null
    }
}
