package com.transtrend.ai.assistant.settings

import com.transtrend.ai.assistant.ModularPluginBackendBundle
import com.transtrend.ai.assistant.index.ProjectIndexService
import com.transtrend.ai.assistant.models.BackendModelsService
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.openapi.ui.ComboBox
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
 */
class AssistantConfigurable(private val project: Project) : Configurable {

    private var panel: JPanel? = null
    private val baseUrlField = JBTextField(30)
    private val useProxyCheckbox = JBCheckBox(ModularPluginBackendBundle.message("settings.use.proxy"))
    private val indexingCheckbox = JBCheckBox(ModularPluginBackendBundle.message("settings.index.enable"))
    private val embeddingUrlField = JBTextField(30)
    private val embeddingModelCombo = ComboBox<String>().apply { isEditable = true }
    private val rebuildButton = JButton(ModularPluginBackendBundle.message("settings.index.rebuild"))
    private val statusLabel = JBLabel()
    private var statusTimer: Timer? = null

    /** Index controls only mean something while indexing is on — grey them out otherwise. */
    private fun updateIndexControlsEnabled() {
        val settings = AssistantSettings.getInstance()
        val indexingOn = indexingCheckbox.isSelected
        embeddingUrlField.isEnabled = indexingOn && settings.embeddingBaseUrlEnvOverride == null
        embeddingModelCombo.isEnabled = indexingOn && settings.embeddingModelEnvOverride == null
        rebuildButton.isEnabled = indexingOn
    }

    override fun getDisplayName(): String =
        ModularPluginBackendBundle.message("settings.display.name")

    override fun createComponent(): JComponent {
        val settings = AssistantSettings.getInstance()
        val envUrl = System.getenv("OLLAMA_BASE_URL")?.takeIf { it.isNotBlank() }
        baseUrlField.emptyText.text = AssistantSettings.DEFAULT_BASE_URL
        baseUrlField.isEnabled = envUrl == null

        val envEmbed = settings.embeddingModelEnvOverride
        populateEmbeddingCandidates()

        rebuildButton.addActionListener { ProjectIndexService.getInstance(project).rebuild() }
        indexingCheckbox.addItemListener { updateIndexControlsEnabled() }
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

        builder.addComponent(useProxyCheckbox)
            .addComponentToRightColumn(hintLabel(
                ModularPluginBackendBundle.message("settings.use.proxy.hint")))

        val envEmbedUrl = settings.embeddingBaseUrlEnvOverride
        embeddingUrlField.emptyText.text =
            ModularPluginBackendBundle.message("settings.index.url.placeholder")
        embeddingUrlField.isEnabled = envEmbedUrl == null

        builder.addComponent(TitledSeparator(ModularPluginBackendBundle.message("settings.index.title")))
            .addComponent(indexingCheckbox)
            .addLabeledComponent(
                ModularPluginBackendBundle.message("settings.index.url"),
                embeddingUrlField,
            )
        if (envEmbedUrl != null) {
            builder.addComponentToRightColumn(hintLabel(
                ModularPluginBackendBundle.message("settings.index.url.env", envEmbedUrl)))
        } else {
            builder.addComponentToRightColumn(hintLabel(
                ModularPluginBackendBundle.message("settings.index.url.hint")))
        }
        builder.addLabeledComponent(
            ModularPluginBackendBundle.message("settings.index.model"),
            embeddingModelCombo,
        )
        if (envEmbed != null) {
            builder.addComponentToRightColumn(hintLabel(
                ModularPluginBackendBundle.message("settings.index.model.env", envEmbed)))
        } else {
            builder.addComponentToRightColumn(hintLabel(
                ModularPluginBackendBundle.message("settings.index.model.hint")))
        }
        val statusRow = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(rebuildButton, BorderLayout.WEST)
            add(statusLabel, BorderLayout.CENTER)
        }
        builder.addComponentToRightColumn(statusRow)

        return builder.addComponentFillVertically(JPanel(), 0).panel.also {
            panel = it
            reset()
            updateIndexControlsEnabled()
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

    private fun comboText(): String =
        (embeddingModelCombo.editor.item?.toString() ?: "").trim()

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
            useProxyCheckbox.isSelected != settings.useProxy ||
            indexingCheckbox.isSelected != settings.indexingEnabled ||
            embeddingUrlField.text.trim().trimEnd('/') != settings.storedEmbeddingBaseUrl ||
            comboText() != settings.storedEmbeddingModel
    }

    override fun apply() {
        val settings = AssistantSettings.getInstance()
        settings.storedBaseUrl = baseUrlField.text
        settings.useProxy = useProxyCheckbox.isSelected
        BackendModelsService.getInstance().refreshAsync()

        val wasEnabled = settings.indexingEnabled
        val modelChanged = comboText() != settings.storedEmbeddingModel
        val embedUrlChanged = embeddingUrlField.text.trim().trimEnd('/') != settings.storedEmbeddingBaseUrl
        settings.indexingEnabled = indexingCheckbox.isSelected
        settings.storedEmbeddingModel = comboText()
        settings.storedEmbeddingBaseUrl = embeddingUrlField.text

        val indexService = ProjectIndexService.getInstance(project)
        when {
            wasEnabled != settings.indexingEnabled -> indexService.onIndexingToggled(settings.indexingEnabled)
            settings.indexingEnabled && (modelChanged || embedUrlChanged) -> indexService.rebuild()
        }
    }

    override fun reset() {
        val settings = AssistantSettings.getInstance()
        baseUrlField.text = settings.storedBaseUrl
        useProxyCheckbox.isSelected = settings.useProxy
        indexingCheckbox.isSelected = settings.indexingEnabled
        embeddingUrlField.text = settings.storedEmbeddingBaseUrl
        embeddingModelCombo.editor.item = settings.storedEmbeddingModel
    }

    override fun disposeUIResources() {
        statusTimer?.stop()
        statusTimer = null
        panel = null
    }
}
