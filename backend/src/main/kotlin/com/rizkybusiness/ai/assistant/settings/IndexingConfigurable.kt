package com.rizkybusiness.ai.assistant.settings

import com.rizkybusiness.ai.assistant.ModularPluginBackendBundle
import com.rizkybusiness.ai.assistant.index.ProjectIndexService
import com.rizkybusiness.ai.assistant.models.BackendModelsService
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
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
 * Project indexing sub-page of the root settings page. Project-level so the indexing
 * controls can reach this project's [ProjectIndexService]; the stored settings themselves
 * are application-wide.
 *
 * Group order (deliberate): configuration first (custom embedding source, model), then
 * the enable toggle directly above the Rebuild button it governs.
 */
class IndexingConfigurable(private val project: Project) : Configurable {

    private var panel: JPanel? = null
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
        ModularPluginBackendBundle.message("settings.indexing.display.name")

    override fun createComponent(): JComponent {
        val settings = AssistantSettings.getInstance()
        val envEmbedUrl = settings.embeddingBaseUrlEnvOverride
        val envEmbedModel = settings.embeddingModelEnvOverride

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
            .addComponent(customEmbedUrlCheckbox)
            .addComponent(embeddingUrlRow)
            .addComponentToRightColumn(embeddingUrlHint)
            .addLabeledComponent(
                ModularPluginBackendBundle.message("settings.index.model"),
                embeddingModelCombo,
            )
        if (envEmbedModel != null) {
            builder.addComponentToRightColumn(SettingsUi.hintLabel(
                ModularPluginBackendBundle.message("settings.index.model.env", envEmbedModel)))
        } else {
            builder.addComponentToRightColumn(SettingsUi.hintLabel(
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
        return indexingCheckbox.isSelected != settings.indexingEnabled ||
            uiEmbeddingUrl() != settings.storedEmbeddingBaseUrl ||
            comboText() != settings.storedEmbeddingModel
    }

    override fun apply() {
        val settings = AssistantSettings.getInstance()
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
