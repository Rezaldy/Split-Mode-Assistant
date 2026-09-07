package com.rizkybusiness.ai.assistant.chatApp.viewmodel

import com.intellij.openapi.Disposable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.rizkybusiness.ai.assistant.ChatMessage
import com.rizkybusiness.ai.assistant.ContextFileDto
import com.rizkybusiness.ai.assistant.FileRefDto
import com.rizkybusiness.ai.assistant.IndexStatusDto
import com.rizkybusiness.ai.assistant.ModelsStateDto
import com.rizkybusiness.ai.assistant.SkillDto
import com.rizkybusiness.ai.assistant.SkillsStateDto

interface ChatViewModelApi : Disposable {
    val chatMessagesFlow: StateFlow<List<ChatMessage>>

    val contextFilesFlow: StateFlow<List<ContextFileDto>>

    val modelsStateFlow: StateFlow<ModelsStateDto>

    fun onModelSelected(name: String)

    fun onRefreshModels()

    val indexStatusFlow: StateFlow<IndexStatusDto>

    fun onRebuildIndex()

    /** Results for the `@` mention popup; empty list hides it. */
    val mentionResultsFlow: StateFlow<List<FileRefDto>>

    /** null clears results; a query triggers a debounced backend search. */
    fun onMentionQuery(query: String?)

    /** Host catalog of skills; the `/` popup filters it locally, no RPC per keystroke. */
    val skillsStateFlow: StateFlow<SkillsStateDto>

    /** Results for the `/` skill popup; empty list hides it. */
    val skillResultsFlow: StateFlow<List<SkillDto>>

    /** null clears results; a query (the text after a leading `/`) filters the cached catalog. */
    fun onSlashQuery(query: String?)

    /** The skill names to send for a leading `/token`, or empty when it matches no enabled skill. */
    fun resolveSkillToken(token: String?): List<String>

    fun onPromptInputChanged(input: String)

    fun onSendMessage(attachments: List<String> = emptyList(), skills: List<String> = emptyList())

    fun onAbortSendingMessage()

    fun searchChatMessagesHandler(): SearchChatMessagesHandler

    val promptInputState: StateFlow<MessageInputState>
}

class ChatViewModel(
    private val coroutineScope: CoroutineScope,
    private val repository: ChatRepositoryApi,
    private val modelsModel: FrontendModelsModel = FrontendModelsModel.getInstance(),
    private val indexModel: FrontendIndexModel? = null,
    private val skillsModel: FrontendSkillsModel? = null,
) : ChatViewModelApi {

    private val _chatMessagesFlow = MutableStateFlow(emptyList<ChatMessage>())

    override val chatMessagesFlow: StateFlow<List<ChatMessage>> = _chatMessagesFlow.asStateFlow()

    override val contextFilesFlow: StateFlow<List<ContextFileDto>> = repository.contextFilesFlow

    override val modelsStateFlow: StateFlow<ModelsStateDto> = modelsModel.stateFlow

    override val indexStatusFlow: StateFlow<IndexStatusDto> =
        indexModel?.statusFlow ?: MutableStateFlow(IndexStatusDto()).asStateFlow()

    override fun onRebuildIndex() {
        coroutineScope.launch {
            indexModel?.rebuild()
        }
    }

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
        private const val MAX_SKILL_RESULTS = 20
        /** The host's user-home skill folders are not VFS-watched; opening the popup rescans at most this often. */
        private const val SKILL_REFRESH_THROTTLE_MS = 5_000L
    }

    override val skillsStateFlow: StateFlow<SkillsStateDto> =
        skillsModel?.stateFlow ?: MutableStateFlow(SkillsStateDto()).asStateFlow()

    private val _skillResults = MutableStateFlow<List<SkillDto>>(emptyList())
    override val skillResultsFlow: StateFlow<List<SkillDto>> = _skillResults.asStateFlow()

    private var currentSlashQuery: String? = null
    private var lastSkillRefreshAt = 0L

    override fun onSlashQuery(query: String?) {
        currentSlashQuery = query
        if (query == null) {
            _skillResults.value = emptyList()
            return
        }
        val now = System.currentTimeMillis()
        if (skillsModel != null && now - lastSkillRefreshAt > SKILL_REFRESH_THROTTLE_MS) {
            lastSkillRefreshAt = now
            coroutineScope.launch {
                try {
                    skillsModel.refresh()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    // The cached catalog still serves the popup; the backend logs the cause.
                }
            }
        }
        recomputeSkillResults()
    }

    private fun recomputeSkillResults() {
        val query = currentSlashQuery ?: return
        _skillResults.value = enabledSkills()
            .filter { it.name.startsWith(query) }
            .take(MAX_SKILL_RESULTS)
    }

    override fun resolveSkillToken(token: String?): List<String> {
        if (token == null) return emptyList()
        return if (enabledSkills().any { it.name == token }) listOf(token) else emptyList()
    }

    private fun enabledSkills(): List<SkillDto> = skillsStateFlow.value.skills.filter { it.enabled }

    private val _promptInputState = MutableStateFlow<MessageInputState>(MessageInputState.Disabled)

    /**
     * True while the backend reports a generation in flight for this tab (thinking
     * placeholder or a streaming reply). Comes from the messages flow, NOT from the local
     * sendMessage call — a generation survives that call (connection blips), and the Stop
     * button must survive with it or a runaway thinking loop becomes unstoppable.
     */
    private val generationActive: StateFlow<Boolean> = repository.messagesFlow
        .map { messages -> messages.any { it.isAIThinkingMessage() || it.isStreaming } }
        .stateIn(coroutineScope, SharingStarted.Eagerly, false)

    /** What the input UI shows: Sending (Stop button) whenever a generation is actually running. */
    override val promptInputState: StateFlow<MessageInputState> =
        combine(_promptInputState, generationActive) { local, active ->
            when {
                active -> MessageInputState.Sending(local.inputText)
                else -> local
            }
        }.stateIn(coroutineScope, SharingStarted.Eagerly, MessageInputState.Disabled)

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
        // A catalog that arrives after the user typed "/" must still populate the open popup.
        skillsStateFlow
            .onEach { recomputeSkillResults() }
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

    override fun onSendMessage(attachments: List<String>, skills: List<String>) {
        currentSendMessageJob = coroutineScope.launch {
            try {
                val currentUserMessage = getCurrentInputTextIfNotEmpty() ?: return@launch
                emitPromptInputState(MessageInputState.Sending(""))

                repository.sendMessage(currentUserMessage, attachments, skills)
            } catch (e: Exception) {
                if (e is CancellationException) throw e

                emitPromptInputState(MessageInputState.SendFailed(e.message ?: "Unknown error", e))
            } finally {
                // The local call ending (done, failed, cancelled, connection blip) never
                // decides the Stop button — generationActive does. Just release the local
                // Sending latch; SendFailed set above survives this.
                if (_promptInputState.value is MessageInputState.Sending) {
                    emitPromptInputState(
                        when (val currentInputState = getCurrentInputTextIfNotEmpty()) {
                            null -> MessageInputState.Disabled
                            else -> MessageInputState.Enabled(currentInputState)
                        }
                    )
                }
            }
        }
    }

    override fun onAbortSendingMessage() {
        // The backend generation is detached from the sendMessage call, so cancelling the
        // local job only stops the wait — the explicit RPC is what stops the generation.
        coroutineScope.launch {
            try {
                repository.abortGeneration()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Nothing to do: the backend generation may already be gone.
            }
        }
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