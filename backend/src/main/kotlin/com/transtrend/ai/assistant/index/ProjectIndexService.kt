package com.transtrend.ai.assistant.index

import com.transtrend.ai.assistant.models.BackendModelsService
import com.transtrend.ai.assistant.ollama.OllamaClientService
import com.transtrend.ai.assistant.ollama.OllamaException
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Builds and owns the project's semantic index (chunks + embeddings).
 *
 * Remote Development guarantee: this class lives in the backend module, so in split mode
 * it exists ONLY in the host IDE process — file enumeration, document reads, embedding
 * traffic, and the on-disk store (under the host's [PathManager.getSystemPath]) all
 * happen where the project files physically are. The client never sees this service.
 */
@Service(Service.Level.PROJECT)
class ProjectIndexService(private val project: Project, private val scope: CoroutineScope) {

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

    @Volatile
    private var buildJob: Job? = null

    private val chunker = Chunker()

    fun indexDirectory(): Path =
        Path.of(PathManager.getSystemPath(), "code-assistant-index", project.locationHash)

    /** Cancels any in-flight build and starts a full rebuild. */
    fun rebuild() {
        buildJob?.cancel()
        buildJob = scope.launch(Dispatchers.IO) {
            try {
                runBuild()
            } catch (e: CancellationException) {
                _status.value = IndexStatus.Idle
                throw e
            } catch (e: OllamaException) {
                _status.value = IndexStatus.Error(e.message.orEmpty())
            } catch (e: Exception) {
                thisLogger().warn("Index build failed", e)
                _status.value = IndexStatus.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun runBuild() {
        _status.value = IndexStatus.Building(filesDone = 0, filesTotal = 0, chunks = 0)

        val embeddingModel = BackendModelsService.getInstance().resolveEmbeddingModel()
        val client = OllamaClientService.getInstance().client()
        val store = IndexStore(indexDirectory())
        val previous = store.load(expectedModel = embeddingModel)
        val previousEntries = previous?.let { toEntryMap(it) } ?: emptyMap()

        val (candidates, totalCandidates) = enumerateFiles()
        val cappedNote = buildCapNote(candidates.size, totalCandidates)
        _status.value = IndexStatus.Building(filesDone = 0, filesTotal = candidates.size, chunks = 0)

        val built = LinkedHashMap<String, FileIndexEntry>()
        var chunksSoFar = 0
        var chunksSinceFlush = 0
        var dims = previous?.meta?.dims ?: 0
        var chunkCapHit = false

        for ((fileIndex, file) in candidates.withIndex()) {
            if (chunksSoFar >= MAX_TOTAL_CHUNKS) {
                chunkCapHit = true
                break
            }
            // One SHORT read action per file — never one long one around the whole loop.
            val text = runReadAction {
                FileDocumentManager.getInstance().getDocument(file)?.text
            }?.take(MAX_FILE_CHARS) ?: continue

            val hash = sha256(text)
            val reused = previousEntries[file.path]?.takeIf { it.contentHash == hash }
            val entry = if (reused != null) {
                reused
            } else {
                val chunks = chunker.chunk(text)
                    .take(MAX_TOTAL_CHUNKS - chunksSoFar)
                if (chunks.isEmpty()) continue
                val vectors = ArrayList<FloatArray>(chunks.size)
                for (batch in chunks.chunked(EMBED_BATCH_SIZE)) {
                    // Sequential batches: the local Ollama is the bottleneck and this
                    // leaves natural interleave points for foreground chat requests.
                    val embedded = client.embed(embeddingModel, batch.map { it.text })
                    embedded.forEach { vectors += VectorMath.normalizeInPlace(it) }
                }
                if (dims == 0) dims = vectors.firstOrNull()?.size ?: 0
                FileIndexEntry(hash, chunks.map { ChunkMeta(it.startLine, it.endLine) }, vectors)
            }

            built[file.path] = entry
            chunksSoFar += entry.chunks.size
            chunksSinceFlush += entry.chunks.size
            _status.value = IndexStatus.Building(fileIndex + 1, candidates.size, chunksSoFar)

            if (chunksSinceFlush >= FLUSH_EVERY_CHUNKS) {
                persist(store, built, embeddingModel, dims, cappedNote)
                chunksSinceFlush = 0
            }
        }

        val finalNote = when {
            chunkCapHit -> listOfNotNull(cappedNote, "chunk cap $MAX_TOTAL_CHUNKS reached")
                .joinToString("; ")
            else -> cappedNote
        }
        persist(store, built, embeddingModel, dims, finalNote?.takeIf { it.isNotBlank() })
        entries = built
        _status.value = IndexStatus.Ready(
            files = built.size,
            chunks = chunksSoFar,
            embeddingModel = embeddingModel,
            cappedNote = finalNote?.takeIf { it.isNotBlank() },
        )
    }

    /** Loads a previously persisted index into memory without rebuilding. */
    fun loadFromDisk() {
        val loaded = IndexStore(indexDirectory()).load() ?: return
        entries = toEntryMap(loaded)
        _status.value = IndexStatus.Ready(
            files = loaded.meta.files.size,
            chunks = loaded.vectors.size,
            embeddingModel = loaded.meta.embeddingModel,
            cappedNote = loaded.meta.cappedNote,
        )
    }

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

    private fun buildCapNote(accepted: Int, total: Int): String? =
        if (total > accepted) "indexed $accepted of $total files (capped)" else null

    private fun persist(
        store: IndexStore,
        built: Map<String, FileIndexEntry>,
        embeddingModel: String,
        dims: Int,
        cappedNote: String?,
    ) {
        if (dims <= 0) return
        val files = built.map { (path, entry) -> FileEntry(path, entry.contentHash, entry.chunks) }
        val vectors = built.values.flatMap { it.vectors }
        store.save(
            IndexMeta(
                schemaVersion = IndexStore.SCHEMA_VERSION,
                embeddingModel = embeddingModel,
                dims = dims,
                cappedNote = cappedNote,
                files = files,
            ),
            vectors,
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
}
