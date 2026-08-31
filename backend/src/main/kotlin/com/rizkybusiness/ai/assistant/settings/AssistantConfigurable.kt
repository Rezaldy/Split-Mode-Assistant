package com.rizkybusiness.ai.assistant.settings

import com.rizkybusiness.ai.assistant.ModularPluginBackendBundle
import com.rizkybusiness.ai.assistant.index.ProjectIndexService
import com.rizkybusiness.ai.assistant.models.BackendModelsService
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer

/**
 * Registered from the backend module: in split mode this panel appears under
 * "Settings on Host", which is correct — the model-source connection and the project
 * index both live on the host. Project-level so the indexing controls can reach this
 * project's [ProjectIndexService]; the stored settings themselves are application-wide.
 *
 * Indexing group order (deliberate): configuration first (custom embedding source,
 * model), then the enable toggle directly above the Rebuild button it governs.
 */
class AssistantConfigurable(private val project: Project) : Configurable {

    private var panel: JPanel? = null
    private val baseUrlField = JBTextField(30)
    private val useProxyCheckbox = JBCheckBox(ModularPluginBackendBundle.message("settings.use.proxy"))
    private val contextTokensField = JBTextField(8)
    private val customEmbedUrlCheckbox =
        JBCheckBox(ModularPluginBackendBundle.message("settings.index.custom.url"))
    private val embeddingUrlField = JBTextField(30)
    private val embeddingUrlRow = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
        isOpaque = false
        add(JBLabel(ModularPluginBackendBundle.message("settings.index.url")), BorderLayout.WEST)
        add(embeddingUrlField, BorderLayout.CENTER)
    }
    private val embeddingUrlHint = JBLabel()
    private val embeddingModelCombo = ComboBox<String>().apply { isEditable = true }
    private val indexingCheckbox = JBCheckBox(ModularPluginBackendBundle.message("settings.index.enable"))
    private val rebuildButton = JButton(ModularPluginBackendBundle.message("settings.index.rebuild"))
    private val statusLabel = JBLabel()
    private var statusTimer: Timer? = null

    override fun getDisplayName(): String =
        ModularPluginBackendBundle.message("settings.display.name")

    override fun createComponent(): JComponent {
        val settings = AssistantSettings.getInstance()
        val envUrl = System.getenv("OLLAMA_BASE_URL")?.takeIf { it.isNotBlank() }
        val envEmbedUrl = settings.embeddingBaseUrlEnvOverride
        val envEmbedModel = settings.embeddingModelEnvOverride

        baseUrlField.emptyText.text = AssistantSettings.DEFAULT_BASE_URL
        baseUrlField.isEnabled = envUrl == null
        populateEmbeddingCandidates()

        rebuildButton.addActionListener { ProjectIndexService.getInstance(project).rebuild() }
        indexingCheckbox.addItemListener { rebuildButton.isEnabled = indexingCheckbox.isSelected }
        customEmbedUrlCheckbox.addItemListener { updateEmbeddingUrlVisibility() }
        customEmbedUrlCheckbox.isEnabled = envEmbedUrl == null
        embeddingUrlField.isEnabled = envEmbedUrl == null
        embeddingModelCombo.isEnabled = envEmbedModel == null
        embeddingUrlHint.font = JBFont.small()
        embeddingUrlHint.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        embeddingUrlHint.text = if (envEmbedUrl != null) {
            ModularPluginBackendBundle.message("settings.index.url.env", envEmbedUrl)
        } else {
            ModularPluginBackendBundle.message("settings.index.url.hint")
        }
        statusLabel.font = JBFont.small()
        statusTimer = Timer(500) { updateStatusLabel() }.also { it.start() }

        val builder = FormBuilder.createFormBuilder()
            .addComponent(TitledSeparator(ModularPluginBackendBundle.message("settings.source.title")))
            .addLabeledComponent(ModularPluginBackendBundle.message("settings.base.url"), baseUrlField)
        if (envUrl != null) {
            builder.addComponentToRightColumn(hintLabel(
                ModularPluginBackendBundle.message("settings.env.override.url", envUrl)))
        }
        builder.addComponentToRightColumn(hintLabel(
            ModularPluginBackendBundle.message("settings.env.override.hint")))
        builder.addLabeledComponent(
            ModularPluginBackendBundle.message("settings.context.tokens"), contextTokensField)
            .addComponentToRightColumn(hintLabel(
                ModularPluginBackendBundle.message("settings.context.tokens.hint")))
        builder.addComponent(useProxyCheckbox)
            .addComponentToRightColumn(hintLabel(
                ModularPluginBackendBundle.message("settings.use.proxy.hint")))

        // Project indexing: configuration first, then enable + Rebuild together.
        builder.addComponent(TitledSeparator(ModularPluginBackendBundle.message("settings.index.title")))
            .addComponent(customEmbedUrlCheckbox)
            .addComponent(embeddingUrlRow)
            .addComponentToRightColumn(embeddingUrlHint)
            .addLabeledComponent(
                ModularPluginBackendBundle.message("settings.index.model"),
                embeddingModelCombo,
            )
        if (envEmbedModel != null) {
            builder.addComponentToRightColumn(hintLabel(
                ModularPluginBackendBundle.message("settings.index.model.env", envEmbedModel)))
        } else {
            builder.addComponentToRightColumn(hintLabel(
                ModularPluginBackendBundle.message("settings.index.model.hint")))
        }
        builder.addComponent(indexingCheckbox)
        val statusRow = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(rebuildButton, BorderLayout.WEST)
            add(statusLabel, BorderLayout.CENTER)
        }
        builder.addComponent(statusRow)

        return builder.addComponentFillVertically(JPanel(), 0).panel.also {
            panel = it
            reset()
            updateStatusLabel()
        }
    }

    private fun hintLabel(text: String) = JBLabel(text).apply {
        font = JBFont.small()
        foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
    }

    /** Candidates come from the already-discovered model list (no blocking call here). */
    private fun populateEmbeddingCandidates() {
        val service = BackendModelsService.getInstance()
        val candidates = service.embeddingCandidatesFromCache()
        embeddingModelCombo.removeAllItems()
        embeddingModelCombo.addItem("")
        candidates.forEach(embeddingModelCombo::addItem)
        if (candidates.isEmpty()) service.refreshAsync()
    }

    private fun updateEmbeddingUrlVisibility() {
        val visible = customEmbedUrlCheckbox.isSelected
        embeddingUrlRow.isVisible = visible
        embeddingUrlHint.isVisible = visible
        panel?.revalidate()
        panel?.repaint()
    }

    private fun comboText(): String =
        (embeddingModelCombo.editor.item?.toString() ?: "").trim()

    /** The embedding URL the UI currently expresses: blank unless the custom box is on. */
    private fun uiEmbeddingUrl(): String =
        if (customEmbedUrlCheckbox.isSelected) embeddingUrlField.text.trim().trimEnd('/') else ""

    private fun updateStatusLabel() {
        statusLabel.text = when (val current = ProjectIndexService.getInstance(project).status.value) {
            is ProjectIndexService.IndexStatus.Idle ->
                ModularPluginBackendBundle.message("index.status.idle")
            is ProjectIndexService.IndexStatus.Building ->
                ModularPluginBackendBundle.message(
                    "index.status.building", current.filesDone, current.filesTotal, current.chunks)
            is ProjectIndexService.IndexStatus.Ready ->
                ModularPluginBackendBundle.message(
                    "index.status.ready", current.files, current.chunks, current.embeddingModel) +
                    (current.cappedNote?.let { " — $it" } ?: "")
            is ProjectIndexService.IndexStatus.Error ->
                ModularPluginBackendBundle.message("index.status.error", current.message)
        }
    }

    override fun isModified(): Boolean {
        val settings = AssistantSettings.getInstance()
        return baseUrlField.text.trim().trimEnd('/') != settings.storedBaseUrl ||
            (contextTokensField.text.trim().toIntOrNull() ?: settings.contextTokens) != settings.contextTokens ||
            useProxyCheckbox.isSelected != settings.useProxy ||
            indexingCheckbox.isSelected != settings.indexingEnabled ||
            uiEmbeddingUrl() != settings.storedEmbeddingBaseUrl ||
            comboText() != settings.storedEmbeddingModel
    }

    override fun apply() {
        val settings = AssistantSettings.getInstance()
        settings.storedBaseUrl = baseUrlField.text
        contextTokensField.text.trim().toIntOrNull()?.let { settings.contextTokens = it }
        contextTokensField.text = settings.contextTokens.toString()
        settings.useProxy = useProxyCheckbox.isSelected
        BackendModelsService.getInstance().refreshAsync()

        val wasEnabled = settings.indexingEnabled
        val modelChanged = comboText() != settings.storedEmbeddingModel
        val embedUrlChanged = uiEmbeddingUrl() != settings.storedEmbeddingBaseUrl
        settings.indexingEnabled = indexingCheckbox.isSelected
        settings.storedEmbeddingModel = comboText()
        settings.storedEmbeddingBaseUrl = uiEmbeddingUrl()

        val indexService = ProjectIndexService.getInstance(project)
        when {
            wasEnabled != settings.indexingEnabled -> indexService.onIndexingToggled(settings.indexingEnabled)
            settings.indexingEnabled && (modelChanged || embedUrlChanged) -> indexService.rebuild()
        }
    }

    override fun reset() {
        val settings = AssistantSettings.getInstance()
        baseUrlField.text = settings.storedBaseUrl
        contextTokensField.text = settings.contextTokens.toString()
        useProxyCheckbox.isSelected = settings.useProxy
        indexingCheckbox.isSelected = settings.indexingEnabled
        rebuildButton.isEnabled = settings.indexingEnabled
        embeddingUrlField.text = settings.storedEmbeddingBaseUrl
        customEmbedUrlCheckbox.isSelected =
            settings.storedEmbeddingBaseUrl.isNotBlank() || settings.embeddingBaseUrlEnvOverride != null
        embeddingModelCombo.editor.item = settings.storedEmbeddingModel
        updateEmbeddingUrlVisibility()
    }

    override fun disposeUIResources() {
        statusTimer?.stop()
        statusTimer = null
        panel = null
    }
}
