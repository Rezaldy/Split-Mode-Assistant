package com.transtrend.ai.assistant.toolWindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.transtrend.ai.assistant.CoroutineScopeHolder
import com.transtrend.ai.assistant.chatApp.ChatAppSample
import com.transtrend.ai.assistant.chatApp.viewmodel.ChatViewModel
import com.transtrend.ai.assistant.chatApp.viewmodel.FrontendChatRepositoryModel

class ModularPluginToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        chatApp(project, toolWindow)
    }

    private fun chatApp(project: Project, toolWindow: ToolWindow) {
        val viewModel = ChatViewModel(
            CoroutineScopeHolder.getInstance(project).createScope(ChatViewModel::class.java.simpleName),
            FrontendChatRepositoryModel.getInstance(project)
        )
        Disposer.register(toolWindow.disposable, viewModel)

        val chatPanel = ChatAppSample(viewModel, project)
        val content = ContentFactory.getInstance().createContent(chatPanel, "Chat App", false)
        toolWindow.contentManager.addContent(content)
    }
}
