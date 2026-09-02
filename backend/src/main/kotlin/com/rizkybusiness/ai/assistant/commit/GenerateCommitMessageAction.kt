package com.rizkybusiness.ai.assistant.commit

import com.rizkybusiness.ai.assistant.ModularPluginBackendBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.ChangeListManager

/**
 * Commit-toolbar button ("Vcs.MessageActionGroup", next to Amend): generates a commit
 * message from the changes included in the commit and streams it into the message field.
 *
 * Registered on the backend: in split mode the VCS model and the commit live on the host,
 * and the platform projects host actions into the client's commit toolbar.
 */
class GenerateCommitMessageAction : DumbAwareAction(
    ModularPluginBackendBundle.message("commit.generate.action.text"),
    ModularPluginBackendBundle.message("commit.generate.action.description"),
    AllIcons.Actions.Lightning,
) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val commitMessage = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL)
        e.presentation.isEnabledAndVisible = project != null && commitMessage != null
        if (project != null) {
            e.presentation.isEnabled =
                commitMessage != null && !CommitMessageGeneratorService.getInstance(project).isRunning
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val commitMessage = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) ?: return
        CommitMessageGeneratorService.getInstance(project).generate(includedChanges(e), commitMessage)
    }

    /**
     * The changes the commit will actually contain: the commit UI's included (checked)
     * changes when the workflow UI is reachable, the active changelist otherwise.
     */
    private fun includedChanges(e: AnActionEvent) =
        e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)?.getIncludedChanges()
            ?.takeIf { it.isNotEmpty() }
            ?: ChangeListManager.getInstance(e.project!!).defaultChangeList.changes.toList()
}
