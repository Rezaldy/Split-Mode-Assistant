package com.rizkybusiness.ai.assistant.settings

import com.rizkybusiness.ai.assistant.ModularPluginBackendBundle
import com.rizkybusiness.ai.assistant.SkillDto
import com.rizkybusiness.ai.assistant.skills.SkillDiscoveryService
import com.rizkybusiness.ai.assistant.skills.SkillLocations
import com.rizkybusiness.ai.assistant.skills.SkillStore
import com.intellij.icons.AllIcons
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.Timer
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * Agent Skills sub-page of the root settings page. Project-level so it can reach this
 * project's [SkillDiscoveryService]; the enable/disable and catalog settings themselves are
 * application-wide, same as the rest of [AssistantSettings].
 *
 * Follows [IndexingConfigurable]'s polling pattern: the discovery service scans
 * asynchronously, so the table is refreshed from a [Timer] rather than blocking the EDT.
 */
class SkillsConfigurable(private val project: Project) : Configurable {

    private var panel: JPanel? = null
    private val hintLabel = SettingsUi.hintLabel("")
    private val tableModel = SkillsTableModel()
    private val table = SkillsTable(tableModel)
    private val catalogCheckbox = JBCheckBox(ModularPluginBackendBundle.message("settings.skills.catalog"))
    private val pendingDisabled = mutableSetOf<String>()
    private var lastSkills: List<SkillDto> = emptyList()
    private var statusTimer: Timer? = null

    override fun getDisplayName(): String =
        ModularPluginBackendBundle.message("settings.skills.display.name")

    override fun createComponent(): JComponent {
        SkillDiscoveryService.getInstance(project).refreshAsync()

        val decoratedTable = ToolbarDecorator.createDecorator(table)
            .disableAddAction()
            .disableUpDownActions()
            .setRemoveAction { removeSelectedSkill() }
            .setRemoveActionUpdater { selectedUploadedSkill() != null }
            .addExtraAction(RefreshAction())
            .apply {
                // Opening the host's file manager only helps when the user sits at the host;
                // in Remote Development the path (copied) is what they need.
                if (RevealFileAction.isDirectoryOpenSupported()) addExtraAction(OpenFolderAction())
            }
            .addExtraAction(CopyPathAction())
            .createPanel()

        val bottomPanel = FormBuilder.createFormBuilder()
            .addComponent(SettingsUi.hintLabel(ModularPluginBackendBundle.message("settings.skills.hint.locations")))
            .addComponent(catalogCheckbox)
            .addComponentToRightColumn(SettingsUi.hintLabel(
                ModularPluginBackendBundle.message("settings.skills.catalog.hint")))
            .addComponent(SettingsUi.hintLabel(ModularPluginBackendBundle.message("settings.skills.hint.import")))
            .panel

        val root = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            add(hintLabel, BorderLayout.NORTH)
            add(decoratedTable, BorderLayout.CENTER)
            add(bottomPanel, BorderLayout.SOUTH)
        }

        statusTimer = Timer(500) { pollSnapshot() }.also { it.start() }

        return root.also {
            panel = it
            reset()
            pollSnapshot()
        }
    }

    private fun pollSnapshot() {
        val snapshot = SkillDiscoveryService.getInstance(project).snapshot()
        hintLabel.text = snapshot.error ?: ""
        hintLabel.isVisible = snapshot.error != null
        if (snapshot.skills != lastSkills) {
            lastSkills = snapshot.skills
            tableModel.setSkills(snapshot.skills)
        }
    }

    private fun selectedUploadedSkill(): SkillDto? {
        val row = table.selectedRow.takeIf { it >= 0 } ?: return null
        return tableModel.skills.getOrNull(table.convertRowIndexToModel(row))?.takeIf { it.uploaded }
    }

    private fun removeSelectedSkill() {
        val skill = selectedUploadedSkill() ?: return
        val confirmed = Messages.showYesNoDialog(
            project,
            ModularPluginBackendBundle.message("settings.skills.delete.confirm", skill.name),
            ModularPluginBackendBundle.message("settings.skills.delete.title"),
            Messages.getQuestionIcon(),
        ) == Messages.YES
        if (!confirmed) return
        ApplicationManager.getApplication().executeOnPooledThread {
            SkillStore(SkillLocations.uploadRoot()).delete(skill.name)
            SkillDiscoveryService.getInstance(project).refreshAsync()
        }
    }

    override fun isModified(): Boolean {
        val settings = AssistantSettings.getInstance()
        return pendingDisabled != settings.disabledSkillNames ||
            catalogCheckbox.isSelected != settings.skillsCatalogInPrompt
    }

    override fun apply() {
        val settings = AssistantSettings.getInstance()
        settings.disabledSkillNames = pendingDisabled.toSet()
        settings.skillsCatalogInPrompt = catalogCheckbox.isSelected
        SkillDiscoveryService.getInstance(project).notifySettingsChanged()
    }

    override fun reset() {
        val settings = AssistantSettings.getInstance()
        pendingDisabled.clear()
        pendingDisabled.addAll(settings.disabledSkillNames)
        catalogCheckbox.isSelected = settings.skillsCatalogInPrompt
        tableModel.fireTableDataChanged()
    }

    override fun disposeUIResources() {
        statusTimer?.stop()
        statusTimer = null
        panel = null
    }

    private fun localizedScope(scope: String): String = when (scope) {
        SkillDiscoveryService.SCOPE_PROJECT -> ModularPluginBackendBundle.message("settings.skills.scope.project")
        SkillDiscoveryService.SCOPE_USER -> ModularPluginBackendBundle.message("settings.skills.scope.user")
        else -> scope
    }

    /** Backs the table; the Enabled column reflects [pendingDisabled], not [AssistantSettings] directly. */
    private inner class SkillsTableModel : AbstractTableModel() {
        var skills: List<SkillDto> = emptyList()
            private set

        fun setSkills(newSkills: List<SkillDto>) {
            skills = newSkills
            fireTableDataChanged()
        }

        override fun getRowCount(): Int = skills.size

        override fun getColumnCount(): Int = 4

        override fun getColumnName(column: Int): String = when (column) {
            0 -> ModularPluginBackendBundle.message("settings.skills.column.enabled")
            1 -> ModularPluginBackendBundle.message("settings.skills.column.name")
            2 -> ModularPluginBackendBundle.message("settings.skills.column.scope")
            3 -> ModularPluginBackendBundle.message("settings.skills.column.location")
            else -> ""
        }

        override fun getColumnClass(columnIndex: Int): Class<*> =
            if (columnIndex == 0) Boolean::class.javaObjectType else String::class.java

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = columnIndex == 0

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val skill = skills[rowIndex]
            return when (columnIndex) {
                0 -> skill.name !in pendingDisabled
                1 -> skill.name
                2 -> localizedScope(skill.scope)
                3 -> skill.location
                else -> ""
            }
        }

        override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
            if (columnIndex != 0) return
            val skill = skills[rowIndex]
            when (value as? Boolean ?: return) {
                true -> pendingDisabled.remove(skill.name)
                false -> pendingDisabled.add(skill.name)
            }
            fireTableCellUpdated(rowIndex, columnIndex)
        }
    }

    /** Row tooltip shows warnings; the Name column gets a warning icon when any are present. */
    private class SkillsTable(private val model: SkillsTableModel) : JBTable(model) {
        init {
            emptyText.text = ModularPluginBackendBundle.message("settings.skills.empty")
            columnModel.getColumn(1).cellRenderer = NameCellRenderer(model)
        }

        override fun getToolTipText(event: MouseEvent): String? {
            val row = rowAtPoint(event.point).takeIf { it >= 0 } ?: return null
            val skill = model.skills.getOrNull(convertRowIndexToModel(row)) ?: return null
            return skill.warnings.takeIf { it.isNotEmpty() }?.joinToString("\n")
        }
    }

    private class NameCellRenderer(private val model: SkillsTableModel) : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val skill = model.skills.getOrNull(table.convertRowIndexToModel(row))
            icon = if (skill != null && skill.warnings.isNotEmpty()) AllIcons.General.Warning else null
            return component
        }
    }

    private inner class RefreshAction : DumbAwareAction(
        ModularPluginBackendBundle.message("settings.skills.refresh"), null, AllIcons.Actions.Refresh,
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            SkillDiscoveryService.getInstance(project).refreshAsync()
        }
    }

    private fun selectedSkillFolder(): Path? {
        val row = table.selectedRow.takeIf { it >= 0 } ?: return null
        val skill = tableModel.skills.getOrNull(table.convertRowIndexToModel(row)) ?: return null
        return Path.of(skill.location).parent
    }

    /** Base for actions that need a selected row; reads Swing state, so updates run on the EDT. */
    private abstract inner class SelectionAction(text: String, icon: javax.swing.Icon) : DumbAwareAction(text, null, icon) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = table.selectedRow >= 0
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class OpenFolderAction : SelectionAction(
        ModularPluginBackendBundle.message("settings.skills.open.folder"), AllIcons.Nodes.Folder,
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            selectedSkillFolder()?.let(RevealFileAction::openDirectory)
        }
    }

    private inner class CopyPathAction : SelectionAction(
        ModularPluginBackendBundle.message("settings.skills.copy.path"), AllIcons.Actions.Copy,
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            selectedSkillFolder()?.let { CopyPasteManager.getInstance().setContents(StringSelection(it.toString())) }
        }
    }
}
