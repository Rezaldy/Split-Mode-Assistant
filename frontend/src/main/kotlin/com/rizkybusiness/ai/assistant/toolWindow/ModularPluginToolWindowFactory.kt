package com.rizkybusiness.ai.assistant.toolWindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.rizkybusiness.ai.assistant.CoroutineScopeHolder
import com.rizkybusiness.ai.assistant.ModularPluginFrontendBundle
import com.rizkybusiness.ai.assistant.chatApp.ChatAppSample
import com.rizkybusiness.ai.assistant.chatApp.viewmodel.ChatTabRepository
import com.rizkybusiness.ai.assistant.chatApp.viewmodel.ChatViewModel
import com.rizkybusiness.ai.assistant.chatApp.viewmodel.FrontendChatRepositoryModel
import com.rizkybusiness.ai.assistant.chatApp.viewmodel.FrontendIndexModel
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class ModularPluginToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val tabCounter = AtomicInteger(0)
        addChatTab(project, toolWindow, tabCounter)

        (toolWindow as? ToolWindowEx)?.setTabActions(object : AnAction(
            ModularPluginFrontendBundle.message("chat.tab.new"),
            null,
            AllIcons.General.Add,
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                addChatTab(project, toolWindow, tabCounter)
            }
        })

        // Closing the last tab must not leave a dead, empty tool window.
        toolWindow.contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun contentRemoved(event: ContentManagerEvent) {
                if (toolWindow.contentManager.contentCount == 0) {
                    addChatTab(project, toolWindow, tabCounter)
                }
            }
        })
    }

    private fun addChatTab(project: Project, toolWindow: ToolWindow, tabCounter: AtomicInteger) {
        // The chat id is minted here and never reused: the backend keys the conversation
        // (history, generation) by it, so every tab is an independent context window.
        val chatId = UUID.randomUUID().toString()
        // One scope per tab, shared by the repository's flows and the view model — the view
        // model's dispose() cancels it, taking the tab's RPC flow collections down with it.
        val tabScope = CoroutineScopeHolder.getInstance(project).createScope("ChatTab-$chatId")
        val viewModel = ChatViewModel(
            tabScope,
            ChatTabRepository(project, chatId, tabScope),
            indexModel = FrontendIndexModel.getInstance(project),
        )

        val title = ModularPluginFrontendBundle.message("chat.tab.title", tabCounter.incrementAndGet())
        val chatPanel = ChatAppSample(viewModel, project)
        val content = ContentFactory.getInstance().createContent(chatPanel, title, false).apply {
            isCloseable = true
            setDisposer(Disposable {
                // Order matters: the backend drop rides the project service scope because
                // the tab's own scopes die with the view model right after.
                FrontendChatRepositoryModel.getInstance(project).closeChatAsync(chatId)
                viewModel.dispose()
            })
        }
        toolWindow.contentManager.addContent(content)
        toolWindow.contentManager.setSelectedContent(content)
    }
}
