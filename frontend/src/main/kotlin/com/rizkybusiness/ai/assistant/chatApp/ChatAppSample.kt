package com.rizkybusiness.ai.assistant.chatApp

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*
import com.rizkybusiness.ai.assistant.CoroutineScopeHolder
import com.rizkybusiness.ai.assistant.chatApp.ui.*
import com.rizkybusiness.ai.assistant.chatApp.ui.utils.ChatAppColors
import com.rizkybusiness.ai.assistant.chatApp.viewmodel.ChatViewModel
import java.awt.*
import javax.swing.*

class ChatAppSample(
    private val viewModel: ChatViewModel,
    private val project: Project
) : JPanel() {

    private val toolbar: ChatToolbar
    private val chatList: ChatList
    private val contextFilesBar: ContextFilesBar
    private val modelsErrorBanner: ModelsErrorBanner
    private val promptInput: PromptInput

    init {
        setupAppearance()

        toolbar = ChatToolbar(viewModel)
        chatList = ChatList(project)
        contextFilesBar = ContextFilesBar()
        modelsErrorBanner = ModelsErrorBanner()
        promptInput = PromptInput(
            onInputChanged = { text -> viewModel.onPromptInputChanged(text) },
            onSend = { _ -> viewModel.onSendMessage(promptInput.currentMentionPaths()) },
            onStop = { _ -> viewModel.onAbortSendingMessage() },
            onMentionQuery = { query -> viewModel.onMentionQuery(query) }
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

    private fun subscribeToViewModelUpdates() {
        val coroutineScope = CoroutineScopeHolder.getInstance(project).createScope(ChatAppSample::class.java.simpleName)

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
