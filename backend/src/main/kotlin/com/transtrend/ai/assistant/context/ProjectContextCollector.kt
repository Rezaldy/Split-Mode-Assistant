package com.transtrend.ai.assistant.context

import com.transtrend.ai.assistant.ContextFileDto
import com.transtrend.ai.assistant.index.ProjectIndexService
import com.transtrend.ai.assistant.index.RetrievalSelector
import com.transtrend.ai.assistant.index.VectorMath
import com.transtrend.ai.assistant.ollama.OllamaClientService
import com.transtrend.ai.assistant.settings.AssistantSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Assembles the project-context block sent to the model, under a hard character budget,
 * and publishes which files that context is built from (shown in the chat UI).
 *
 * Budget priority: `@`-mentioned files first, then the remainder splits 60% open files /
 * 40% index-retrieved snippets (each side's unused share spills to the other). With the
 * index disabled, not ready, or empty of hits, behavior is identical to open-files-only.
 * Retrieval failures NEVER block chat — they degrade to non-indexed context and log.
 */
@Service(Service.Level.PROJECT)
class ProjectContextCollector(private val project: Project) : Disposable {

    companion object {
        const val DEFAULT_BUDGET_CHARS = 24_000
        const val MAX_RETRIEVED_SNIPPET_CHARS = 4_000
        const val SOURCE_OPEN = "open"
        const val SOURCE_RETRIEVED = "retrieved"
        private const val RETRIEVED_BUDGET_SHARE = 0.4

        fun getInstance(project: Project): ProjectContextCollector =
            project.getService(ProjectContextCollector::class.java)
    }

    private val _contextFiles = MutableStateFlow<List<ContextFileDto>>(emptyList())

    /** Files currently counted as context; updates on editor changes and after retrieval. */
    val contextFiles: StateFlow<List<ContextFileDto>> = _contextFiles.asStateFlow()

    @Volatile
    private var lastRetrieved: List<ContextFileDto> = emptyList()

    init {
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) = refreshContextFiles()
                override fun fileClosed(source: FileEditorManager, file: VirtualFile) = refreshContextFiles()
            },
        )
        refreshContextFiles()
    }

    suspend fun collect(
        question: String? = null,
        mentionPaths: List<String> = emptyList(),
        budgetChars: Int = DEFAULT_BUDGET_CHARS,
    ): String {
        val retrieved = if (question != null) retrieve(question, mentionPaths) else emptyList()
        lastRetrieved = retrieved.map { it.first }
        refreshContextFiles()
        return runReadAction { assemble(mentionPaths, retrieved.map { it.second }, budgetChars) }
    }

    // --- Assembly (inside one read action; only VFS/document reads) ----------

    private fun assemble(
        mentionPaths: List<String>,
        retrievedBlocks: List<String>,
        budgetChars: Int,
    ): String {
        val documentManager = FileDocumentManager.getInstance()
        val block = StringBuilder()
        val includedPaths = mutableSetOf<String>()

        // @-mentioned files first: they take priority in the budget.
        val fileSystem = LocalFileSystem.getInstance()
        for (path in mentionPaths) {
            if (block.length >= budgetChars) break
            val file = fileSystem.findFileByPath(path)
            when {
                file == null || file.isDirectory ->
                    block.append("// File: ").append(path).append(" [not found]\n\n")
                file.fileType.isBinary ->
                    block.append("// File: ").append(path).append(" [binary file omitted]\n\n")
                else -> {
                    includedPaths += file.path
                    appendFile(block, file.path + " [mentioned]", documentManager.getDocument(file)?.text, budgetChars)
                }
            }
        }

        // Remaining budget: open files get 60%, retrieval is guaranteed 40% when it has
        // hits; whatever open files leave unused spills into the retrieval share.
        val remaining = (budgetChars - block.length).coerceAtLeast(0)
        val retrievedFloor = if (retrievedBlocks.isEmpty()) 0 else (remaining * RETRIEVED_BUDGET_SHARE).toInt()
        val openCap = block.length + (remaining - retrievedFloor)

        for (file in openTextFiles()) {
            if (block.length >= openCap) break
            if (file.path in includedPaths) continue
            appendFile(block, file.path, documentManager.getDocument(file)?.text, openCap)
        }

        for (rendered in retrievedBlocks) {
            if (block.length + rendered.length > budgetChars) break
            block.append(rendered)
        }
        return block.toString()
    }

    private fun appendFile(block: StringBuilder, header: String, text: String?, budgetChars: Int) {
        if (text == null) return
        block.append("// File: ").append(header).append('\n')
        val remaining = budgetChars - block.length
        if (text.length <= remaining) {
            block.append(text)
        } else {
            block.append(text, 0, remaining.coerceAtLeast(0)).append("\n// [truncated]")
        }
        block.append("\n\n")
    }

    // --- Retrieval (network outside read actions; short RA per snippet) ------

    private suspend fun retrieve(
        question: String,
        mentionPaths: List<String>,
    ): List<Pair<ContextFileDto, String>> {
        return try {
            if (!AssistantSettings.getInstance().indexingEnabled) return emptyList()
            val indexService = ProjectIndexService.getInstance(project)
            val entries = indexService.entries
            val embeddingModel = indexService.currentEmbeddingModel
            if (entries.isEmpty() || embeddingModel == null) return emptyList()

            val client = OllamaClientService.getInstance().embeddingClient()
            val query = VectorMath.normalizeInPlace(client.embed(embeddingModel, listOf(question)).first())
            // Open + mentioned files are already in the context in full — never retrieve them.
            val excluded = mentionPaths.toSet() + runReadAction { openTextFiles().map { it.path } }
            RetrievalSelector.select(query, entries, excluded).mapNotNull { renderHit(it) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            thisLogger().warn("Retrieval failed; continuing without indexed context", e)
            emptyList()
        }
    }

    private fun renderHit(hit: RetrievalSelector.Hit): Pair<ContextFileDto, String>? = runReadAction {
        val file = LocalFileSystem.getInstance().findFileByPath(hit.path) ?: return@runReadAction null
        if (file.isDirectory || file.fileType.isBinary) return@runReadAction null
        val text = FileDocumentManager.getInstance().getCachedDocument(file)?.text
            ?: runCatching { VfsUtilCore.loadText(file) }.getOrNull()
            ?: return@runReadAction null
        val lines = text.lines()
        val from = (hit.startLine - 1).coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        val to = hit.endLine.coerceAtMost(lines.size)
        if (to <= from) return@runReadAction null
        val snippet = lines.subList(from, to).joinToString("\n").take(MAX_RETRIEVED_SNIPPET_CHARS)
        val dto = ContextFileDto(file.path, file.name, source = SOURCE_RETRIEVED)
        dto to "// File: ${file.path} (lines ${hit.startLine}-${hit.endLine}) [retrieved]\n$snippet\n\n"
    }

    // --- Context bar ----------------------------------------------------------

    private fun refreshContextFiles() {
        val open = openTextFiles().map { ContextFileDto(path = it.path, fileName = it.name) }
        val openPaths = open.map { it.path }.toSet()
        _contextFiles.value = open + lastRetrieved.filter { it.path !in openPaths }
    }

    private fun openTextFiles(): List<VirtualFile> =
        FileEditorManager.getInstance(project).openFiles.filter { !it.fileType.isBinary }

    override fun dispose() {}
}
