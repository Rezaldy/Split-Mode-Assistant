package com.rizkybusiness.ai.assistant.index

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

@Serializable
data class ChunkMeta(val startLine: Int, val endLine: Int)

@Serializable
data class FileEntry(
    val path: String,
    val contentHash: String,
    val chunks: List<ChunkMeta>,
)

@Serializable
data class IndexMeta(
    val schemaVersion: Int,
    val embeddingModel: String,
    val dims: Int,
    /** Human-readable note when caps truncated coverage; null = full coverage. */
    val cappedNote: String? = null,
    val files: List<FileEntry> = emptyList(),
)

/** [meta] plus one normalized vector per chunk, in meta order (file order, then chunk order). */
data class LoadedIndex(val meta: IndexMeta, val vectors: List<FloatArray>)

/**
 * Persists the index as `meta.json` (kotlinx.serialization) + `vectors.bin` (raw
 * little-endian float32 via JDK NIO — JSON-encoded vectors would be tens of MB and
 * seconds to parse). Writes go to temp files first, then move into place, so a crash
 * mid-save never corrupts an existing index.
 */
class IndexStore(private val directory: Path) {

    companion object {
        const val SCHEMA_VERSION = 1
        private const val META_FILE = "meta.json"
        private const val VECTORS_FILE = "vectors.bin"
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun save(meta: IndexMeta, vectors: List<FloatArray>) {
        val expected = meta.files.sumOf { it.chunks.size }
        require(vectors.size == expected) { "vector count ${vectors.size} != chunk count $expected" }
        Files.createDirectories(directory)

        val metaTmp = directory.resolve("$META_FILE.tmp")
        Files.writeString(metaTmp, json.encodeToString(IndexMeta.serializer(), meta))

        val vectorsTmp = directory.resolve("$VECTORS_FILE.tmp")
        FileChannel.open(
            vectorsTmp,
            StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
        ).use { channel ->
            for (vector in vectors) {
                val buffer = ByteBuffer.allocate(vector.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                buffer.asFloatBuffer().put(vector)
                channel.write(buffer)
            }
        }

        Files.move(vectorsTmp, directory.resolve(VECTORS_FILE), StandardCopyOption.REPLACE_EXISTING)
        Files.move(metaTmp, directory.resolve(META_FILE), StandardCopyOption.REPLACE_EXISTING)
    }

    /**
     * Loads a stored index, or null when absent/corrupt/mismatched (wrong schema version,
     * different embedding model or dims — those invalidate every vector, so callers rebuild).
     */
    fun load(expectedModel: String? = null): LoadedIndex? {
        val metaPath = directory.resolve(META_FILE)
        val vectorsPath = directory.resolve(VECTORS_FILE)
        if (!Files.isRegularFile(metaPath) || !Files.isRegularFile(vectorsPath)) return null
        return try {
            val meta = json.decodeFromString(IndexMeta.serializer(), Files.readString(metaPath))
            if (meta.schemaVersion != SCHEMA_VERSION) return null
            if (meta.dims <= 0) return null
            if (expectedModel != null && meta.embeddingModel != expectedModel) return null

            val chunkCount = meta.files.sumOf { it.chunks.size }
            val bytes = Files.readAllBytes(vectorsPath)
            if (bytes.size != chunkCount * meta.dims * Float.SIZE_BYTES) return null

            val floatBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val vectors = ArrayList<FloatArray>(chunkCount)
            repeat(chunkCount) {
                val vector = FloatArray(meta.dims)
                floatBuffer.get(vector)
                vectors += vector
            }
            LoadedIndex(meta, vectors)
        } catch (e: IOException) {
            null
        } catch (e: kotlinx.serialization.SerializationException) {
            null
        }
    }

    fun delete() {
        listOf(META_FILE, VECTORS_FILE, "$META_FILE.tmp", "$VECTORS_FILE.tmp").forEach {
            Files.deleteIfExists(directory.resolve(it))
        }
    }
}
