package com.transtrend.ai.assistant.chatApp

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*
import com.transtrend.ai.assistant.CoroutineScopeHolder
import com.transtrend.ai.assistant.chatApp.ui.*
import com.transtrend.ai.assistant.chatApp.ui.utils.ChatAppColors
import com.transtrend.ai.assistant.chatApp.viewmodel.ChatViewModel
import java.awt.*
import javax.swing.*

class ChatAppSample(
    private val viewModel: ChatViewModel,
    private val project: Project
) : JPanel() {

    private val toolbar: ChatToolbar
    private val chatList: ChatList
    private val contextFilesBar: ContextFilesBar
    private val promptInput: PromptInput

    init {
        setupAppearance()

        toolbar = ChatToolbar(viewModel)
        chatList = ChatList()
        contextFilesBar = ContextFilesBar()
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

        add(toolbar, BorderLayout.NORTH)
        add(chatList, BorderLayout.CENTER)
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
