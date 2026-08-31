package com.transtrend.ai.assistant.chatApp.viewmodel

import com.intellij.openapi.Disposable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.transtrend.ai.assistant.ChatMessage
import com.transtrend.ai.assistant.ContextFileDto
import com.transtrend.ai.assistant.FileRefDto
import com.transtrend.ai.assistant.ModelsStateDto

interface ChatViewModelApi : Disposable {
    val chatMessagesFlow: StateFlow<List<ChatMessage>>

    val contextFilesFlow: StateFlow<List<ContextFileDto>>

    val modelsStateFlow: StateFlow<ModelsStateDto>

    fun onModelSelected(name: String)

    fun onRefreshModels()

    /** Results for the `@` mention popup; empty list hides it. */
    val mentionResultsFlow: StateFlow<List<FileRefDto>>

    /** null clears results; a query triggers a debounced backend search. */
    fun onMentionQuery(query: String?)

    fun onPromptInputChanged(input: String)

    fun onSendMessage(attachments: List<String> = emptyList())

    fun onAbortSendingMessage()

    fun searchChatMessagesHandler(): SearchChatMessagesHandler

    val promptInputState: StateFlow<MessageInputState>
}

class ChatViewModel(
    private val coroutineScope: CoroutineScope,
    private val repository: ChatRepositoryApi,
    private val modelsModel: FrontendModelsModel = FrontendModelsModel.getInstance()
) : ChatViewModelApi {

    private val _chatMessagesFlow = MutableStateFlow(emptyList<ChatMessage>())

    override val chatMessagesFlow: StateFlow<List<ChatMessage>> = _chatMessagesFlow.asStateFlow()

    override val contextFilesFlow: StateFlow<List<ContextFileDto>> = repository.contextFilesFlow

    override val modelsStateFlow: StateFlow<ModelsStateDto> = modelsModel.stateFlow

    override fun onModelSelected(name: String) {
        coroutineScope.launch {
            modelsModel.select(name)
        }
    }

    override fun onRefreshModels() {
        coroutineScope.launch {
            modelsModel.refresh()
        }
    }

    private val _mentionResults = MutableStateFlow<List<FileRefDto>>(emptyList())
    override val mentionResultsFlow: StateFlow<List<FileRefDto>> = _mentionResults.asStateFlow()

    private var mentionSearchJob: Job? = null

    override fun onMentionQuery(query: String?) {
        mentionSearchJob?.cancel()
        if (query.isNullOrBlank()) {
            _mentionResults.value = emptyList()
            return
        }
        mentionSearchJob = coroutineScope.launch {
            delay(MENTION_SEARCH_DEBOUNCE_MS)
            _mentionResults.value = try {
                repository.searchFiles(query)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                emptyList()
            }
        }
    }

    companion object {
        private const val MENTION_SEARCH_DEBOUNCE_MS = 250L
    }

    private val _promptInputState = MutableStateFlow<MessageInputState>(MessageInputState.Disabled)
    override val promptInputState: StateFlow<MessageInputState> = _promptInputState.asStateFlow()

    private val searchChatMessagesHandler: SearchChatMessagesHandler = SearchChatMessagesHandlerImpl(
        coroutineScope = coroutineScope,
        messagesFlow = repository.messagesFlow
    )

    /**
     * A nullable [Job] instance used to manage the coroutine responsible for sending a message.
     * This property holds a reference to the currently active job related to the `onSendMessage`
     * operation in the [ChatViewModel]. It enables tracking, cancellation, and lifecycle management
     * of the send message process.
     */
    private var currentSendMessageJob: Job? = null

    init {
        // Emit all messages from the repository to the UI
        repository
            .messagesFlow
            .onEach { messages -> _chatMessagesFlow.value = messages }
            .launchIn(coroutineScope)
    }

    override fun onPromptInputChanged(input: String) {
        val currentPromptInputState = _promptInputState.value
        _promptInputState.value = when {
            currentPromptInputState is MessageInputState.Sending -> MessageInputState.Sending(input)
            input.isEmpty() -> MessageInputState.Disabled
            else -> MessageInputState.Enabled(input)
        }
    }

    override fun onSendMessage(attachments: List<String>) {
        currentSendMessageJob = coroutineScope.launch {
            try {
                val currentUserMessage = getCurrentInputTextIfNotEmpty() ?: return@launch
                emitPromptInputState(MessageInputState.Sending(""))

                repository.sendMessage(currentUserMessage, attachments)

                emitPromptInputState(
                    when (val currentInputState = getCurrentInputTextIfNotEmpty()) {
                        null -> MessageInputState.Disabled
                        else -> MessageInputState.Enabled(currentInputState)
                    }
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e

                emitPromptInputState(MessageInputState.SendFailed(e.message ?: "Unknown error", e))
            }
        }
    }

    override fun onAbortSendingMessage() {
        currentSendMessageJob?.cancel()

        emitPromptInputState(
            when (val currentPromptInput = getCurrentInputTextIfNotEmpty()) {
                null -> MessageInputState.Disabled
                else -> MessageInputState.Enabled(currentPromptInput)
            }
        )
    }

    override fun searchChatMessagesHandler(): SearchChatMessagesHandler = searchChatMessagesHandler

    override fun dispose() {
        coroutineScope.cancel()
    }

    private fun emitPromptInputState(state: MessageInputState) {
        _promptInputState.value = state
    }

    private fun getCurrentInputTextIfNotEmpty(): String? = _promptInputState.value.inputText.takeIf { it.isNotBlank() }
}