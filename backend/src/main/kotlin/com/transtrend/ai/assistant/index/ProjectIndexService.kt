package com.transtrend.ai.assistant.index

import com.transtrend.ai.assistant.ModularPluginBackendBundle
import com.transtrend.ai.assistant.models.BackendModelsService
import com.transtrend.ai.assistant.ollama.OllamaClientService
import com.transtrend.ai.assistant.ollama.OllamaException
import com.transtrend.ai.assistant.settings.AssistantSettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.util.messages.MessageBusConnection
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Builds and owns the project's semantic index (chunks + embeddings), settings-gated.
 *
 * Remote Development guarantee: this class lives in the backend module, so in split mode
 * it exists ONLY in the host IDE process — file enumeration, document reads, embedding
 * traffic, and the on-disk store (under the host's [PathManager.getSystemPath]) all
 * happen where the project files physically are. The client never sees this service.
 *
 * Index errors must never block chat: failures land in [status] + a notification, and
 * retrieval callers degrade to non-indexed context.
 */
@OptIn(FlowPreview::class)
@Service(Service.Level.PROJECT)
class ProjectIndexService(private val project: Project, private val scope: CoroutineScope) : Disposable {

    companion object {
        fun getInstance(project: Project): ProjectIndexService =
            project.getService(ProjectIndexService::class.java)

        // Big-repo safety caps. Cap hits are reported in the status, never silent.
        const val MAX_FILES = 4_000
        const val MAX_FILE_BYTES = 512L * 1024
        const val MAX_FILE_CHARS = 200_000
        const val MAX_TOTAL_CHUNKS = 25_000
        const val EMBED_BATCH_SIZE = 16
        const val FLUSH_EVERY_CHUNKS = 500
        const val INCREMENTAL_DEBOUNCE_MS = 3_000L

        private const val NOTIFICATION_GROUP = "Split Mode Assistant"
    }

    sealed interface IndexStatus {
        data object Idle : IndexStatus
        data class Building(val filesDone: Int, val filesTotal: Int, val chunks: Int) : IndexStatus
        data class Ready(
            val files: Int,
            val chunks: Int,
            val embeddingModel: String,
            val cappedNote: String?,
        ) : IndexStatus
        data class Error(val message: String) : IndexStatus
    }

    /** In-memory index entry per file; vectors are normalized. */
    data class FileIndexEntry(
        val contentHash: String,
        val chunks: List<ChunkMeta>,
        val vectors: List<FloatArray>,
    )

    private val _status = MutableStateFlow<IndexStatus>(IndexStatus.Idle)
    val status: StateFlow<IndexStatus> = _status.asStateFlow()

    @Volatile
    var entries: Map<String, FileIndexEntry> = emptyMap()
        private set

    /** Model the in-memory index was built with; query embedding must use the same one. */
    @Volatile
    var currentEmbeddingModel: String? = null
        private set

    @Volatile
    private var buildJob: Job? = null

    private val chunker = Chunker()

    // Incremental machinery: VFS events add paths; a debounced pass re-embeds changes.
    private var vfsConnection: MessageBusConnection? = null
    private val dirtyPaths = ConcurrentHashMap.newKeySet<String>()
    private val removedPaths = ConcurrentHashMap.newKeySet<String>()
    private val changeTickle = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    init {
        scope.launch {
            changeTickle.debounce(INCREMENTAL_DEBOUNCE_MS).collect {
                buildJob?.join()
                runCatching { processIncremental() }.onFailure { e ->
                    if (e is CancellationException) throw e
                    thisLogger().warn("Incremental index update failed", e)
                    _status.value = IndexStatus.Error(e.message ?: e.javaClass.simpleName)
                }
            }
        }
        if (AssistantSettings.getInstance().indexingEnabled) {
            attachVfsListener()
            scope.launch(Dispatchers.IO) {
                loadFromDisk()
                if (entries.isEmpty()) rebuild()
            }
        }
    }

    /** Reacts to the settings toggle: on = listen + (re)build if needed, off = stop (index kept on disk). */
    fun onIndexingToggled(enabled: Boolean) {
        if (enabled) {
            attachVfsListener()
            scope.launch(Dispatchers.IO) {
                if (entries.isEmpty()) loadFromDisk()
                if (entries.isEmpty()) rebuild()
            }
        } else {
            detachVfsListener()
            scope.launch { buildJob?.cancelAndJoin(); _status.value = IndexStatus.Idle }
        }
    }

    fun indexDirectory(): Path =
        Path.of(PathManager.getSystemPath(), "code-assistant-index", project.locationHash)

    /** Cancels any in-flight build and starts a full rebuild. */
    fun rebuild() {
        val previousJob = buildJob
        buildJob = scope.launch(Dispatchers.IO) {
            // Wait the old build out completely: both jobs write the same store paths,
            // and its cancellation handler must not stomp this build's status.
            previousJob?.cancelAndJoin()
            try {
                runBuild()
                notifyResult()
            } catch (e: CancellationException) {
                _status.value = IndexStatus.Idle
                throw e
            } catch (e: OllamaException) {
                _status.value = IndexStatus.Error(e.message.orEmpty())
                notifyResult()
            } catch (e: Exception) {
                thisLogger().warn("Index build failed", e)
                _status.value = IndexStatus.Error(e.message ?: e.javaClass.simpleName)
                notifyResult()
            }
        }
    }

    private suspend fun runBuild() {
        _status.value = IndexStatus.Building(filesDone = 0, filesTotal = 0, chunks = 0)

        val embeddingModel = BackendModelsService.getInstance().resolveEmbeddingModel()
        val store = IndexStore(indexDirectory())
        val previous = store.load(expectedModel = embeddingModel)
        val previousEntries = previous?.let { toEntryMap(it) } ?: emptyMap()

        val (candidates, totalCandidates) = enumerateFiles()
        val fileCapNote = if (totalCandidates > candidates.size) {
            "indexed ${candidates.size} of $totalCandidates files (capped)"
        } else null
        _status.value = IndexStatus.Building(filesDone = 0, filesTotal = candidates.size, chunks = 0)

        val built = LinkedHashMap<String, FileIndexEntry>()
        var dims = previous?.meta?.dims ?: 0
        var chunksSoFar = 0
        var chunksSinceFlush = 0
        var chunkCapHit = false

        // Cross-file batching: chunks queue up across files and are embedded 16 at a time,
        // so many small files don't degenerate into one HTTP call each.
        val client = OllamaClientService.getInstance().client()
        val pendingTexts = mutableListOf<String>()
        val pendingTargets = mutableListOf<PendingFile>()
        val incomplete = LinkedHashMap<String, PendingFile>()

        suspend fun flushBatch() {
            if (pendingTexts.isEmpty()) return
            val embedded = client.embed(embeddingModel, pendingTexts.toList())
            embedded.forEachIndexed { i, vector ->
                pendingTargets[i].vectors += VectorMath.normalizeInPlace(vector)
            }
            if (dims == 0) dims = embedded.firstOrNull()?.size ?: 0
            pendingTexts.clear()
            pendingTargets.clear()
            val done = incomplete.values.filter { it.vectors.size == it.chunks.size }
            for (pending in done) {
                incomplete.remove(pending.path)
                built[pending.path] = FileIndexEntry(
                    pending.hash,
                    pending.chunks.map { ChunkMeta(it.startLine, it.endLine) },
                    pending.vectors.toList(),
                )
                chunksSoFar += pending.chunks.size
                chunksSinceFlush += pending.chunks.size
            }
            if (chunksSinceFlush >= FLUSH_EVERY_CHUNKS) {
                persist(store, built, embeddingModel, dims, fileCapNote)
                chunksSinceFlush = 0
            }
        }

        for ((fileIndex, file) in candidates.withIndex()) {
            if (chunksSoFar + incomplete.values.sumOf { it.chunks.size } >= MAX_TOTAL_CHUNKS) {
                chunkCapHit = true
                break
            }
            val text = readFileText(file) ?: continue
            val hash = sha256(text)
            val reused = previousEntries[file.path]?.takeIf { it.contentHash == hash }
            if (reused != null) {
                built[file.path] = reused
                chunksSoFar += reused.chunks.size
                if (dims == 0) dims = reused.vectors.firstOrNull()?.size ?: 0
            } else {
                val allChunks = chunker.chunk(text)
                val room = MAX_TOTAL_CHUNKS - chunksSoFar - incomplete.values.sumOf { it.chunks.size }
                val chunks = if (allChunks.size > room) {
                    chunkCapHit = true
                    allChunks.take(room)
                } else allChunks
                if (chunks.isEmpty()) continue
                val pending = PendingFile(file.path, hash, chunks)
                incomplete[file.path] = pending
                for (chunk in chunks) {
                    pendingTexts += chunk.text
                    pendingTargets += pending
                    if (pendingTexts.size >= EMBED_BATCH_SIZE) flushBatch()
                }
            }
            _status.value = IndexStatus.Building(fileIndex + 1, candidates.size, chunksSoFar)
        }
        flushBatch()

        val finalNote = listOfNotNull(
            fileCapNote,
            "chunk cap $MAX_TOTAL_CHUNKS reached".takeIf { chunkCapHit },
        ).joinToString("; ").takeIf { it.isNotBlank() }

        persist(store, built, embeddingModel, dims, finalNote)
        entries = built
        currentEmbeddingModel = embeddingModel
        _status.value = IndexStatus.Ready(built.size, chunksSoFar, embeddingModel, finalNote)
    }

    private data class PendingFile(
        val path: String,
        val hash: String,
        val chunks: List<Chunk>,
        val vectors: MutableList<FloatArray> = mutableListOf(),
    )

    /** Loads a previously persisted index into memory without rebuilding. */
    fun loadFromDisk() {
        val loaded = IndexStore(indexDirectory()).load() ?: return
        entries = toEntryMap(loaded)
        currentEmbeddingModel = loaded.meta.embeddingModel
        _status.value = IndexStatus.Ready(
            files = loaded.meta.files.size,
            chunks = loaded.vectors.size,
            embeddingModel = loaded.meta.embeddingModel,
            cappedNote = loaded.meta.cappedNote,
        )
    }

    // --- Incremental updates -------------------------------------------------

    private fun attachVfsListener() {
        if (vfsConnection != null) return
        vfsConnection = project.messageBus.connect(this).also { connection ->
            connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                override fun after(events: List<VFileEvent>) = recordEvents(events)
            })
        }
    }

    private fun detachVfsListener() {
        vfsConnection?.disconnect()
        vfsConnection = null
        dirtyPaths.clear()
        removedPaths.clear()
    }

    private fun recordEvents(events: List<VFileEvent>) {
        var changed = false
        for (event in events) {
            when (event) {
                is VFileContentChangeEvent -> { dirtyPaths += event.file.path; changed = true }
                is VFileCreateEvent -> { event.file?.let { dirtyPaths += it.path; changed = true } }
                is VFileDeleteEvent -> { removedPaths += event.file.path; changed = true }
                is VFileMoveEvent -> {
                    removedPaths += event.oldPath
                    dirtyPaths += event.file.path
                    changed = true
                }
                is VFilePropertyChangeEvent -> if (event.propertyName == VirtualFile.PROP_NAME) {
                    removedPaths += event.oldPath
                    dirtyPaths += event.file.path
                    changed = true
                }
                else -> Unit
            }
        }
        if (changed) changeTickle.tryEmit(Unit)
    }

    private suspend fun processIncremental() {
        if (!AssistantSettings.getInstance().indexingEnabled) return
        val model = currentEmbeddingModel ?: return
        if (entries.isEmpty()) return

        val removed = removedPaths.toList().also { removedPaths.removeAll(it.toSet()) }
        val dirty = dirtyPaths.toList().also { dirtyPaths.removeAll(it.toSet()) }
        if (removed.isEmpty() && dirty.isEmpty()) return

        val updated = LinkedHashMap(entries)
        removed.forEach { updated.remove(it) }

        val client = OllamaClientService.getInstance().client()
        val fileSystem = LocalFileSystem.getInstance()
        for (path in dirty) {
            val file = fileSystem.findFileByPath(path) ?: continue
            val inContent = runReadAction {
                !file.isDirectory && !file.fileType.isBinary && file.length in 1..MAX_FILE_BYTES &&
                    ProjectFileIndex.getInstance(project).isInContent(file)
            }
            if (!inContent) continue
            val text = readFileText(file) ?: continue
            val hash = sha256(text)
            if (updated[path]?.contentHash == hash) continue
            val chunks = chunker.chunk(text)
            if (chunks.isEmpty()) {
                updated.remove(path)
                continue
            }
            val vectors = ArrayList<FloatArray>(chunks.size)
            for (batch in chunks.chunked(EMBED_BATCH_SIZE)) {
                client.embed(model, batch.map { it.text }).forEach {
                    vectors += VectorMath.normalizeInPlace(it)
                }
            }
            updated[path] = FileIndexEntry(hash, chunks.map { ChunkMeta(it.startLine, it.endLine) }, vectors)
        }

        entries = updated
        val dims = updated.values.firstOrNull()?.vectors?.firstOrNull()?.size ?: return
        persist(IndexStore(indexDirectory()), updated, model, dims, null)
        val previousNote = (status.value as? IndexStatus.Ready)?.cappedNote
        _status.value = IndexStatus.Ready(
            files = updated.size,
            chunks = updated.values.sumOf { it.chunks.size },
            embeddingModel = model,
            cappedNote = previousNote,
        )
    }

    // --- Helpers -------------------------------------------------------------

    private fun readFileText(file: VirtualFile): String? = runReadAction {
        // Cached document (open files, unsaved edits) or raw VFS text: unopened files
        // have no unsaved edits, so loadText is equivalent without materializing a
        // Document per indexed file.
        FileDocumentManager.getInstance().getCachedDocument(file)?.text
            ?: runCatching { VfsUtilCore.loadText(file) }.getOrNull()
    }?.take(MAX_FILE_CHARS)

    private fun enumerateFiles(): Pair<List<VirtualFile>, Int> = runReadAction {
        val accepted = mutableListOf<VirtualFile>()
        var totalCandidates = 0
        ProjectFileIndex.getInstance(project).iterateContent { file ->
            if (!file.isDirectory && !file.fileType.isBinary && file.length in 1..MAX_FILE_BYTES) {
                totalCandidates++
                if (accepted.size < MAX_FILES) accepted += file
            }
            true
        }
        accepted to totalCandidates
    }

    private fun persist(
        store: IndexStore,
        built: Map<String, FileIndexEntry>,
        embeddingModel: String,
        dims: Int,
        cappedNote: String?,
    ) {
        if (dims <= 0) return
        store.save(
            IndexMeta(
                schemaVersion = IndexStore.SCHEMA_VERSION,
                embeddingModel = embeddingModel,
                dims = dims,
                cappedNote = cappedNote,
                files = built.map { (path, entry) -> FileEntry(path, entry.contentHash, entry.chunks) },
            ),
            built.values.flatMap { it.vectors },
        )
    }

    private fun toEntryMap(loaded: LoadedIndex): Map<String, FileIndexEntry> {
        val result = LinkedHashMap<String, FileIndexEntry>()
        var offset = 0
        for (file in loaded.meta.files) {
            val vectors = loaded.vectors.subList(offset, offset + file.chunks.size).toList()
            offset += file.chunks.size
            result[file.path] = FileIndexEntry(file.contentHash, file.chunks, vectors)
        }
        return result
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun notifyResult() {
        val (message, type) = when (val current = status.value) {
            is IndexStatus.Ready -> ModularPluginBackendBundle.message(
                "index.notify.ready", current.files, current.chunks,
            ) + (current.cappedNote?.let { " ($it)" } ?: "") to NotificationType.INFORMATION
            is IndexStatus.Error -> ModularPluginBackendBundle.message(
                "index.notify.error", current.message,
            ) to NotificationType.WARNING
            else -> return
        }
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(message, type)
            .notify(project)
    }

    override fun dispose() {}
}
