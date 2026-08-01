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
    private val promptInput: PromptInput

    init {
        setupAppearance()

        toolbar = ChatToolbar(viewModel)
        chatList = ChatList()
        promptInput = PromptInput(
            onInputChanged = { text -> viewModel.onPromptInputChanged(text) },
            onSend = { _ -> viewModel.onSendMessage() },
            onStop = { _ -> viewModel.onAbortSendingMessage() }
        )

        add(toolbar, BorderLayout.NORTH)
        add(chatList, BorderLayout.CENTER)
        add(promptInput, BorderLayout.SOUTH)

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
