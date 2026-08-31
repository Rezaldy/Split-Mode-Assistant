package com.rizkybusiness.ai.assistant.index

import com.rizkybusiness.ai.assistant.settings.AssistantSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Wakes the index service on project open (when indexing is enabled) so the VFS listener
 * attaches and the startup reconciliation runs — otherwise edits made before the first
 * chat interaction would go unnoticed and the sync indicator could lie green.
 */
class IndexStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (!AssistantSettings.getInstance().indexingEnabled) return
        ProjectIndexService.getInstance(project)
    }
}
