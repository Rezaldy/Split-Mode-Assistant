package com.rizkybusiness.ai.assistant.chatApp

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import kotlinx.coroutines.*
import com.rizkybusiness.ai.assistant.CoroutineScopeHolder
import com.rizkybusiness.ai.assistant.SkillUploadResultDto
import com.rizkybusiness.ai.assistant.chatApp.ui.*
import com.rizkybusiness.ai.assistant.chatApp.ui.utils.ChatAppColors
import com.rizkybusiness.ai.assistant.chatApp.viewmodel.ChatViewModel
import com.rizkybusiness.ai.assistant.skills.SkillImporter
import java.awt.*
import javax.swing.*

private const val IMPORT_BALLOON_FADEOUT_MS = 6_000L

class ChatAppSample(
    private val viewModel: ChatViewModel,
    private val project: Project
) : JPanel() {

    private val toolbar: ChatToolbar
    private val chatList: ChatList
    private val contextFilesBar: ContextFilesBar
    private val modelsErrorBanner: ModelsErrorBanner
    private val promptInput: PromptInput

    private val uiScope = CoroutineScopeHolder.getInstance(project).createScope(ChatAppSample::class.java.simpleName)
    private val skillImporter = SkillImporter(project, uiScope)

    init {
        setupAppearance()

        toolbar = ChatToolbar(
            viewModel,
            onImportSkill = { anchor ->
                skillImporter.importInteractively(anchor) { result -> showImportResult(anchor, result) }
            },
        )
        chatList = ChatList(project)
        contextFilesBar = ContextFilesBar()
        modelsErrorBanner = ModelsErrorBanner()
        promptInput = PromptInput(
            onInputChanged = { text -> viewModel.onPromptInputChanged(text) },
            onSend = { _ ->
                viewModel.onSendMessage(
                    attachments = promptInput.currentMentionPaths(),
                    skills = viewModel.resolveSkillToken(promptInput.leadingSkillToken()),
                )
            },
            onStop = { _ -> viewModel.onAbortSendingMessage() },
            onMentionQuery = { query -> viewModel.onMentionQuery(query) },
            onSlashQuery = { query -> viewModel.onSlashQuery(query) },
        )

        val bottomPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(contextFilesBar, BorderLayout.NORTH)
            add(promptInput, BorderLayout.CENTER)
        }

        val centerPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(modelsErrorBanner, BorderLayout.NORTH)
            add(chatList, BorderLayout.CENTER)
        }

        add(toolbar, BorderLayout.NORTH)
        add(centerPanel, BorderLayout.CENTER)
        add(bottomPanel, BorderLayout.SOUTH)

        subscribeToViewModelUpdates()
    }

    private fun setupAppearance() {
        layout = BorderLayout()
        background = ChatAppColors.Panel.background
    }

    /** Result balloon anchored on the import button; the catalog itself refreshes through the flow. */
    private fun showImportResult(anchor: JComponent, result: SkillUploadResultDto) {
        JBPopupFactory.getInstance()
            .createHtmlTextBalloonBuilder(
                result.message,
                if (result.success) MessageType.INFO else MessageType.ERROR,
                null,
            )
            .setFadeoutTime(IMPORT_BALLOON_FADEOUT_MS)
            .createBalloon()
            .show(RelativePoint.getSouthOf(anchor), Balloon.Position.below)
    }

    private fun subscribeToViewModelUpdates() {
        val coroutineScope = uiScope

        coroutineScope.launch {
            viewModel.skillResultsFlow.collect { results ->
                withContext(Dispatchers.EDT) {
                    promptInput.showSkillResults(results)
                }
            }
        }

        coroutineScope.launch {
            viewModel.chatMessagesFlow.collect { messages ->
                withContext(Dispatchers.EDT) {
                    chatList.setMessages(messages)
                }
            }
        }

        coroutineScope.launch {
            viewModel.promptInputState.collect { state ->
                promptInput.updateState(state)
            }
        }

        coroutineScope.launch {
            viewModel.contextFilesFlow.collect { files ->
                withContext(Dispatchers.EDT) {
                    contextFilesBar.setFiles(files)
                }
            }
        }

        coroutineScope.launch {
            viewModel.modelsStateFlow.collect { state ->
                withContext(Dispatchers.EDT) {
                    toolbar.updateModels(state)
                    modelsErrorBanner.setError(state.error)
                }
            }
        }

        coroutineScope.launch {
            viewModel.mentionResultsFlow.collect { results ->
                withContext(Dispatchers.EDT) {
                    promptInput.showMentionResults(results)
                }
            }
        }

        coroutineScope.launch {
            viewModel.indexStatusFlow.collect { status ->
                withContext(Dispatchers.EDT) {
                    toolbar.updateIndexStatus(status)
                }
            }
        }

        coroutineScope.launch {
            viewModel.searchChatMessagesHandler().searchStateFlow.collect { searchState ->
                withContext(Dispatchers.EDT) {
                    toolbar.updateSearchState(searchState)
                    chatList.updateSearchHighlights(searchState)

                    val currentResultId = searchState.currentSelectedSearchResultId
                    if (currentResultId != null) {
                        chatList.scrollToMessage(currentResultId)
                    }
                }
            }
        }
    }
}
