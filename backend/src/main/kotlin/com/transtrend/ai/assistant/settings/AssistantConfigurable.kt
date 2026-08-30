package com.transtrend.ai.assistant.settings

import com.transtrend.ai.assistant.ModularPluginBackendBundle
import com.transtrend.ai.assistant.models.BackendModelsService
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Registered from the backend module: in split mode this panel appears under
 * "Settings on Host", which is correct — the model-source connection is made from there.
 */
class AssistantConfigurable : Configurable {

    private var panel: JPanel? = null
    private val baseUrlField = JBTextField(30)

    override fun getDisplayName(): String =
        ModularPluginBackendBundle.message("settings.display.name")

    override fun createComponent(): JComponent {
        val envUrl = System.getenv("OLLAMA_BASE_URL")?.takeIf { it.isNotBlank() }
        baseUrlField.emptyText.text = AssistantSettings.DEFAULT_BASE_URL
        baseUrlField.isEnabled = envUrl == null

        val builder = FormBuilder.createFormBuilder()
            .addLabeledComponent(ModularPluginBackendBundle.message("settings.base.url"), baseUrlField)
        if (envUrl != null) {
            builder.addComponentToRightColumn(
                JBLabel(ModularPluginBackendBundle.message("settings.env.override.url", envUrl)).apply {
                    font = JBFont.small()
                    foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
                }
            )
        }
        builder.addComponentToRightColumn(
            JBLabel(ModularPluginBackendBundle.message("settings.env.override.hint")).apply {
                font = JBFont.small()
                foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            }
        )
        return builder.addComponentFillVertically(JPanel(), 0).panel.also {
            panel = it
            reset()
        }
    }

    override fun isModified(): Boolean =
        baseUrlField.text.trim().trimEnd('/') != AssistantSettings.getInstance().storedBaseUrl

    override fun apply() {
        AssistantSettings.getInstance().storedBaseUrl = baseUrlField.text
        // Base URL may have changed: rediscover models so the dropdown follows.
        BackendModelsService.getInstance().refreshAsync()
    }

    override fun reset() {
        baseUrlField.text = AssistantSettings.getInstance().storedBaseUrl
    }

    override fun disposeUIResources() {
        panel = null
    }
}
