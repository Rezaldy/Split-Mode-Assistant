# Embeddings and the Project Index — A Step-by-Step Walkthrough

This document explains, in detail, how Split Mode Assistant uses **embeddings** to find relevant code in your project and hand it to the chat model. It follows one file from "the IDE opened a project" all the way to "the model saw a snippet of it", and it shows the actual code at each step.

It assumes you are a developer but does not assume you know anything about embeddings, vector search, or this codebase. If you only want the short version, read the *Project indexing* section of [DOCUMENTATION.md](DOCUMENTATION.md). This file is the long version.

Code excerpts below are copied from the real sources and trimmed for readability. When a snippet and the source disagree, the source wins — file paths are given so you can open them.

---

## Table of contents

1. [The problem embeddings solve](#1-the-problem-embeddings-solve)
2. [What an embedding actually is](#2-what-an-embedding-actually-is)
3. [The big picture: two phases](#3-the-big-picture-two-phases)
4. [Where the code lives](#4-where-the-code-lives)
5. [Phase 1 — building the index](#5-phase-1--building-the-index)
   - 5.1 [What triggers a build](#51-what-triggers-a-build)
   - 5.2 [Choosing the embedding model](#52-choosing-the-embedding-model)
   - 5.3 [Enumerating project files](#53-enumerating-project-files)
   - 5.4 [Reading and hashing each file](#54-reading-and-hashing-each-file)
   - 5.5 [Chunking](#55-chunking)
   - 5.6 [Embedding chunks in batches](#56-embedding-chunks-in-batches)
   - 5.7 [Normalizing vectors](#57-normalizing-vectors)
   - 5.8 [Persisting the index to disk](#58-persisting-the-index-to-disk)
   - 5.9 [Reporting progress to the UI](#59-reporting-progress-to-the-ui)
6. [Keeping the index fresh](#6-keeping-the-index-fresh)
7. [Phase 2 — answering a question](#7-phase-2--answering-a-question)
   - 7.1 [The entry point](#71-the-entry-point)
   - 7.2 [Embedding the question](#72-embedding-the-question)
   - 7.3 [Scoring every chunk](#73-scoring-every-chunk)
   - 7.4 [Merging adjacent hits](#74-merging-adjacent-hits)
   - 7.5 [Rendering snippets](#75-rendering-snippets)
   - 7.6 [Fitting snippets into the context budget](#76-fitting-snippets-into-the-context-budget)
   - 7.7 [What the model finally sees](#77-what-the-model-finally-sees)
8. [Failure policy](#8-failure-policy)
9. [Split mode: what runs where](#9-split-mode-what-runs-where)
10. [Try it yourself](#10-try-it-yourself)
11. [Design decisions and FAQ](#11-design-decisions-and-faq)
12. [Glossary](#12-glossary)

---

## 1. The problem embeddings solve

When you ask the assistant *"what happens when the model source is unreachable?"*, the chat model can only answer well if the relevant code is in its prompt. But the project might have thousands of files, and the prompt has a hard budget of 24,000 characters. Something has to pick the few files that matter.

Plain keyword search is a poor picker. It looks for your exact words. The answer lives in a function called `mapConnectErrors` inside `OllamaClient.kt`, which catches `ConnectException` and turns it into a user-facing error. Neither "unreachable" nor "model source" appears anywhere near it, so keyword search finds nothing.

Embeddings solve this by comparing **meaning** instead of words. The plugin converts every chunk of code into a list of numbers that captures what the code is *about*. Your question is converted the same way. Chunks whose numbers are "close" to the question's numbers are about the same thing, even if they share no vocabulary.

This technique is commonly called **RAG** (Retrieval-Augmented Generation): retrieve relevant text first, then let the model generate an answer with that text in front of it.

## 2. What an embedding actually is

An **embedding** is a fixed-length array of floating-point numbers, called a **vector**, produced by an embedding model from a piece of text. For the `nomic-embed-text` model the vector has 768 numbers. Other models use 384, 1024, or more. The count is called the vector's **dimensions** (`dims` in this codebase).

The only property that matters to us: **texts with similar meaning produce vectors that point in similar directions.**

### Measuring "similar direction": cosine similarity

The standard way to compare two vectors is **cosine similarity** — the cosine of the angle between them. It ranges from `-1` (opposite) through `0` (unrelated) to `1` (identical direction).

A tiny 2-dimensional example makes the idea concrete:

```
a = (3, 4)      b = (4, 3)      c = (-4, 3)
```

`a` and `b` point in roughly the same direction (small angle), while `c` points somewhere quite different.

### The normalization trick

Computing the cosine requires dividing by the length of both vectors every time. That is wasteful if you compare one query against tens of thousands of stored vectors. So the plugin **normalizes** every vector once, when it is stored: it scales the vector so its length is exactly `1`. After that, the cosine similarity of two normalized vectors is simply their **dot product** — multiply matching elements and add them up.

Continuing the example, dividing each vector by its length (5 for all three) gives:

```
a = (0.6,  0.8)
b = (0.8,  0.6)
c = (-0.8, 0.6)

a · b = 0.6*0.8 + 0.8*0.6 = 0.96     → very similar
a · c = 0.6*-0.8 + 0.8*0.6 = 0.00    → unrelated
```

That is the whole of the math the plugin uses. Here it is in code — [VectorMath.kt](backend/src/main/kotlin/com/rizkybusiness/ai/assistant/index/VectorMath.kt):

```kotlin
/** Pure vector helpers. Vectors are L2-normalized at write time, so cosine = dot product. */
object VectorMath {

    fun normalizeInPlace(vector: FloatArray): FloatArray {
        var sum = 0.0
        for (v in vector) sum += v * v
        val norm = sqrt(sum)
        if (norm > 0.0) {
            for (i in vector.indices) vector[i] = (vector[i] / norm).toFloat()
        }
        return vector
    }

    fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }
}
```

No linear-algebra library, no vector database. Two loops.

## 3. The big picture: two phases

Everything happens in two separate phases that run at different times.

```
PHASE 1 — INDEX TIME (once, then incrementally on file changes)

   project files ──► read + hash ──► chunk ──► embed (Ollama /api/embed) ──► normalize
                                                                                │
                                              ┌─────────────────────────────────┘
                                              ▼
                                   in-memory map path → vectors
                                              │
                                              ▼
                                   meta.json + vectors.bin on disk


PHASE 2 — QUESTION TIME (every chat message)

   your question ──► embed (same model) ──► normalize ──► dot product vs. every stored vector
                                                                     │
                                                                     ▼
                                              keep score ≥ 0.30, take top 12, merge neighbours
                                                                     │
                                                                     ▼
                                               read those line ranges from the files
                                                                     │
                                                                     ▼
                                       fit into the 24k-char budget after mentions/selection/open files
                                                                     │
                                                                     ▼
                                                     system prompt sent to the chat model
```

Phase 1 is expensive (one HTTP call per 16 chunks, thousands of chunks) and runs in the background. Phase 2 is cheap (one HTTP call for the question, then a few milliseconds of arithmetic) and runs on every message.

## 4. Where the code lives

All of it is in `backend/`. In split mode that means the host process — see [section 9](#9-split-mode-what-runs-where).

| File | Role |
|---|---|
| `backend/.../index/ProjectIndexService.kt` | Orchestrates everything in Phase 1: enumerates files, chunks, embeds, persists, watches for changes. One instance per project. |
| `backend/.../index/Chunker.kt` | Splits a file's text into overlapping line-based chunks. Pure Kotlin, unit-tested. |
| `backend/.../index/IndexStore.kt` | Reads and writes `meta.json` + `vectors.bin`. |
| `backend/.../index/VectorMath.kt` | Normalize and dot product. |
| `backend/.../index/RetrievalSelector.kt` | Phase 2 scoring: floor, top-k, adjacent-chunk merging. Pure Kotlin, unit-tested. |
| `backend/.../index/BackendIndexApi.kt` | RPC implementation the UI calls for status and Rebuild. |
| `backend/.../index/IndexStartupActivity.kt` | Wakes the service when a project opens. |
| `backend/.../context/ProjectContextCollector.kt` | Phase 2 caller: embeds the question, runs retrieval, and assembles the final context block under the budget. |
| `backend/.../ollama/OllamaClient.kt` | The `embed()` HTTP call. |
| `backend/.../models/BackendModelsService.kt` | Picks which embedding model to use. |
| `backend/.../settings/AssistantSettings.kt` | The on/off switch and the embedding URL/model settings. |
| `shared/.../IndexApi.kt` | The RPC interface. Only status crosses the wire — never vectors or file contents. |

Tests: `backend/src/test/kotlin/.../index/` has `ChunkerTest`, `VectorMathTest`, `IndexStoreTest`, and `RetrievalSelectorTest`. They are the best executable documentation of the edge cases.

---

## 5. Phase 1 — building the index

### 5.1 What triggers a build

Indexing is **off by default**. The user turns it on in Settings ("Index project for retrieval"). From then on, three things can start work:

1. **Project open.** `IndexStartupActivity` runs when a project opens and, if indexing is enabled, simply asks for the service instance. Creating the instance is what starts everything:

   ```kotlin
   class IndexStartupActivity : ProjectActivity {
       override suspend fun execute(project: Project) {
           if (!AssistantSettings.getInstance().indexingEnabled) return
           ProjectIndexService.getInstance(project)
       }
   }
   ```

2. **The settings toggle** flipping to on (`onIndexingToggled(true)`).
3. **The Rebuild button** in Settings or in the chat header's sync indicator, which reaches the backend through `IndexApi.rebuild(projectId)`.

When the service is constructed with indexing enabled, its `init` block does the startup sequence:

```kotlin
if (AssistantSettings.getInstance().indexingEnabled) {
    attachVfsListener()
    scope.launch(Dispatchers.IO) {
        loadFromDisk()
        // Changes made before this service woke up produced no VFS events —
        // reconcile stored hashes against the current files so the sync
        // indicator can't show green over a stale index.
        if (entries.isEmpty()) rebuild() else reconcileWithDisk()
    }
}
```

In words: start listening for file changes, try to load a previously saved index from disk, and then either build from scratch (nothing on disk) or check the loaded index against the current files (section 6). A full build is therefore rare — usually once per project, and again only after a Rebuild or an embedding-model change.

`rebuild()` cancels any build already running and starts a fresh one on the IO dispatcher. Every outcome — success, cancellation, or error — ends in a status update and, for success/error, an IDE notification.

### 5.2 Choosing the embedding model

Chat models and embedding models are different things. A chat model (`llama3`, `qwen2.5-coder`, …) generates text. An embedding model (`nomic-embed-text`, `bge-m3`, `all-minilm`, …) generates vectors. You need one of each pulled in Ollama.

The plugin picks the embedding model with a three-level precedence, in [BackendModelsService.kt](backend/src/main/kotlin/com/rizkybusiness/ai/assistant/models/BackendModelsService.kt):

```kotlin
private val EMBED_NAME_HINTS = listOf("embed", "bge", "minilm", "arctic")

suspend fun resolveEmbeddingModel(): String {
    val settings = AssistantSettings.getInstance()
    settings.embeddingModelEnvOverride?.let { return it }                       // 1. OLLAMA_EMBED_MODEL
    settings.storedEmbeddingModel.takeIf { it.isNotBlank() }?.let { return it } // 2. Settings field
    val client = OllamaClientService.getInstance().embeddingClient()
    return client.listModels().firstOrNull { name ->                            // 3. auto-pick by name
        EMBED_NAME_HINTS.any { hint -> hint in name.lowercase() }
    } ?: throw OllamaException(ModularPluginBackendBundle.message("error.no.embed.model", client.baseUrl))
}
```

The auto-pick is a name heuristic because Ollama's `GET /api/tags` does not say which models are embedding models. If no name matches, the build fails with a clear message ("No embedding model at … Pull one first (`ollama pull nomic-embed-text`) or set `OLLAMA_EMBED_MODEL`"). That failure shows in the sync indicator and a notification, and chat keeps working without retrieval.

**Embeddings can go to a different Ollama instance than chat.** `OllamaClientService.embeddingClient()` uses `OLLAMA_EMBED_BASE_URL`, then the "Embedding source URL" setting, then falls back to the main model-source URL. This lets a team run a small embedding model locally and a large chat model on a GPU box, or the reverse.

### 5.3 Enumerating project files

The service asks the IDE for every file in the project's **content roots** — the same set you see in the Project view, excluding libraries, SDKs, and external dependencies. This uses only platform-level APIs (no language plugins), which is what keeps the plugin working in PyCharm and WebStorm as well as IntelliJ IDEA.

```kotlin
const val MAX_FILES = 4_000
const val MAX_FILE_BYTES = 512L * 1024

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
```

Three filters apply: not a directory, not a binary file type (images, jars, class files…), and between 1 byte and 512 KB. Empty files have nothing to embed; huge files are usually generated or minified and would swamp the index.

The whole walk happens inside `runReadAction` because IntelliJ's project model may only be read while holding the read lock.

The **4,000-file cap** is a safety limit for very large repositories. When it is hit, the count of skipped files is recorded in a "capped" note that the user sees in the status text — caps are never silent.

### 5.4 Reading and hashing each file

For each candidate file the service reads its text and computes a SHA-256 hash of it:

```kotlin
const val MAX_FILE_CHARS = 200_000

private fun readFileText(file: VirtualFile): String? = runReadAction {
    // Cached document (open files, unsaved edits) or raw VFS text
    FileDocumentManager.getInstance().getCachedDocument(file)?.text
        ?: runCatching { VfsUtilCore.loadText(file) }.getOrNull()
}?.take(MAX_FILE_CHARS)

private fun sha256(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}
```

Two details worth understanding:

- **Why `getCachedDocument` first?** If the file is open in an editor with unsaved edits, the IDE's in-memory document has the newest text. Indexing that instead of the stale disk copy means retrieval matches what the user is looking at.
- **Why hash?** The hash is the key to cheap rebuilds. Before embedding a file, the build checks whether the previous index already has an entry for that path with the *same* hash. If so, the old chunks and vectors are reused as-is and no HTTP call is made:

  ```kotlin
  val hash = sha256(text)
  val reused = previousEntries[file.path]?.takeIf { it.contentHash == hash }
  if (reused != null) {
      built[file.path] = reused
      chunksSoFar += reused.chunks.size
  } else {
      val allChunks = chunker.chunk(text)
      // ... queue chunks for embedding
  }
  ```

  A Rebuild on an unchanged project therefore finishes in seconds, doing only file reads and hashing.

### 5.5 Chunking

An embedding model has an input limit, and a vector for an entire 500-line file would be a blurry average of everything in it. So each file is cut into **chunks** of at most 2,000 characters, and each chunk gets its own vector.

The chunker is line-based: it never cuts in the middle of a line, and consecutive chunks **overlap** by up to 8 lines (at most 200 characters) so that a function signature at the end of one chunk is still visible at the start of the next.

[Chunker.kt](backend/src/main/kotlin/com/rizkybusiness/ai/assistant/index/Chunker.kt), the core loop:

```kotlin
/** One embeddable slice of a file. Lines are 1-based and inclusive. */
data class Chunk(val text: String, val startLine: Int, val endLine: Int)

class Chunker(
    private val maxChunkChars: Int = 2_000,
    private val overlapLines: Int = 8,
    private val maxOverlapChars: Int = 200,
) {
    fun chunk(text: String): List<Chunk> {
        // buffer holds (lineNumber, lineText) pairs for the chunk being built
        text.lines().forEachIndexed { index, line ->
            val lineNo = index + 1
            if (line.length > maxChunkChars) {
                // a single enormous line (minified JS, generated data): hard-split it, no overlap
                ...
                return@forEachIndexed
            }
            if (buffer.isNotEmpty() && bufferChars + line.length + 1 > maxChunkChars) {
                emitBuffer(keepOverlap = true)   // emit chunk, keep the last few lines as overlap
            }
            buffer.addLast(lineNo to line)
            bufferChars += line.length + 1       // +1 for the newline
        }
        // trailing content — but never a chunk that is only the retained overlap
        ...
    }
}
```

Note that a `Chunk` carries **line numbers**, not just text. This matters later: the index stores only line ranges, and Phase 2 re-reads the real file to render the snippet. That keeps the on-disk index small and means the snippet the model sees is always the *current* file content.

#### Worked example

To make the mechanics visible, imagine a chunker configured with `maxChunkChars = 30` and `overlapLines = 1` (the real values are 2,000 and 8), fed this 6-line file:

```
1  fun a() {
2    return 1
3  }
4  fun b() {
5    return 2
6  }
```

Character accounting (each line's length plus 1 for the newline):

| Step | Line | Running chars | Action |
|---|---|---|---|
| 1 | `fun a() {` | 11 | add |
| 2 | `  return 1` | 22 | add |
| 3 | `}` | 24 | add |
| 4 | `fun b() {` | 24 + 11 = 35 > 30 | **emit chunk 1 = lines 1–3**, keep line 3 as overlap, then add line 4 (13) |
| 5 | `  return 2` | 24 | add |
| 6 | `}` | 26 | add |
| end | | | **emit chunk 2 = lines 3–6** |

Result:

```
Chunk(startLine=1, endLine=3, text="fun a() {\n  return 1\n}")
Chunk(startLine=3, endLine=6, text="}\nfun b() {\n  return 2\n}")
```

Line 3 appears in both chunks — that is the overlap. With real settings, a typical 300-line Kotlin file becomes 4–6 chunks.

`ChunkerTest` covers the corner cases: blank files, a single line longer than the budget, and making sure a trailing chunk consisting only of overlap is never emitted.

### 5.6 Embedding chunks in batches

Chunk texts are sent to Ollama's `POST /api/embed` endpoint, which accepts a list of inputs and returns one vector per input. The HTTP call in [OllamaClient.kt](backend/src/main/kotlin/com/rizkybusiness/ai/assistant/ollama/OllamaClient.kt) uses the JDK's built-in `HttpClient` (no third-party HTTP library, per project rules):

```kotlin
@Serializable
internal data class OllamaEmbedRequest(val model: String, val input: List<String>)

@Serializable
internal data class OllamaEmbedResponse(val embeddings: List<List<Float>> = emptyList())

/** Embeds [inputs] via `/api/embed`; returns one vector per input, in order. */
suspend fun embed(model: String, inputs: List<String>): List<FloatArray> = withContext(Dispatchers.IO) {
    val body = json.encodeToString(OllamaEmbedRequest.serializer(), OllamaEmbedRequest(model, inputs))
    val request = HttpRequest.newBuilder(URI.create("$baseUrl/api/embed"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    val response = mapConnectErrors { http.send(request, HttpResponse.BodyHandlers.ofString()) }
    if (response.statusCode() != 200) throw httpError(response.statusCode(), response.body(), model)
    val parsed = json.decodeFromString<OllamaEmbedResponse>(response.body())
    if (parsed.embeddings.size != inputs.size) {
        throw OllamaException(/* "embed returned N vectors for M inputs" */)
    }
    parsed.embeddings.map { it.toFloatArray() }
}
```

On the wire, a request and its response look like this (vectors shortened):

```json
POST http://localhost:11434/api/embed
{
  "model": "nomic-embed-text",
  "input": [
    "fun a() {\n  return 1\n}",
    "}\nfun b() {\n  return 2\n}"
  ]
}
```

```json
{
  "model": "nomic-embed-text",
  "embeddings": [
    [0.0123, -0.0456, 0.0789, ...],   // 768 numbers
    [0.0231, -0.0119, 0.0402, ...]    // 768 numbers
  ]
}
```

#### Batching across files

One HTTP round trip per chunk would be slow, and one per *file* is still slow for projects with many tiny files. So the build keeps a queue of pending chunk texts **across files** and sends them 16 at a time:

```kotlin
const val EMBED_BATCH_SIZE = 16

val pendingTexts = mutableListOf<String>()
val pendingTargets = mutableListOf<PendingFile>()   // which file each queued text belongs to

for (chunk in chunks) {
    pendingTexts += chunk.text
    pendingTargets += pending
    if (pendingTexts.size >= EMBED_BATCH_SIZE) flushBatch()
}
```

`flushBatch()` calls `client.embed(...)`, hands each returned vector back to the file it came from, and finalizes any file whose chunks are now all embedded. Batches are sent **sequentially**, not in parallel — Ollama processes one request at a time per model anyway, and sequential calls keep memory use predictable.

A file that is split across two batches simply waits in an `incomplete` map until its last vector arrives.

### 5.7 Normalizing vectors

Immediately on arrival, every vector is normalized in place (section 2). This is the one and only time normalization happens for stored vectors:

```kotlin
embedded.forEachIndexed { i, vector ->
    pendingTargets[i].vectors += VectorMath.normalizeInPlace(vector)
}
```

The vector's length (`dims`) is also captured from the first response and recorded in the index metadata. Every vector in an index must have the same `dims`; that is guaranteed by using a single model.

### 5.8 Persisting the index to disk

The finished (or partially finished — see below) index is written by [IndexStore.kt](backend/src/main/kotlin/com/rizkybusiness/ai/assistant/index/IndexStore.kt) into a per-project folder on the **host** machine:

```
<IDE system path>/code-assistant-index/<project locationHash>/
├── meta.json
└── vectors.bin
```

`PathManager.getSystemPath()` is the IDE's system directory, which by default is roughly:

| OS | Typical location |
|---|---|
| Windows | `%LOCALAPPDATA%\JetBrains\<IDE><version>\` |
| macOS | `~/Library/Caches/JetBrains/<IDE><version>/` |
| Linux | `~/.cache/JetBrains/<IDE><version>/` |

#### `meta.json` — the structure

Everything except the numbers is stored as JSON via `kotlinx.serialization`:

```kotlin
@Serializable data class ChunkMeta(val startLine: Int, val endLine: Int)

@Serializable data class FileEntry(
    val path: String,
    val contentHash: String,
    val chunks: List<ChunkMeta>,
)

@Serializable data class IndexMeta(
    val schemaVersion: Int,
    val embeddingModel: String,
    val dims: Int,
    val cappedNote: String? = null,
    val files: List<FileEntry> = emptyList(),
)
```

A real file looks like:

```json
{
  "schemaVersion": 1,
  "embeddingModel": "nomic-embed-text",
  "dims": 768,
  "cappedNote": null,
  "files": [
    {
      "path": "C:/Users/me/project/backend/src/main/kotlin/.../OllamaClient.kt",
      "contentHash": "3f9a...c21e",
      "chunks": [
        { "startLine": 1,   "endLine": 62  },
        { "startLine": 55,  "endLine": 118 },
        { "startLine": 111, "endLine": 180 }
      ]
    },
    ...
  ]
}
```

Notice what is **not** there: chunk text. Only line ranges are stored, and Phase 2 re-reads the file. This keeps `meta.json` small and avoids a second, stale copy of your source on disk.

#### `vectors.bin` — the numbers

Vectors are written as raw little-endian 32-bit floats, back to back, in the same order as the chunks in `meta.json` (file order, then chunk order). No headers, no separators:

```kotlin
FileChannel.open(vectorsTmp, CREATE, WRITE, TRUNCATE_EXISTING).use { channel ->
    for (vector in vectors) {
        val buffer = ByteBuffer.allocate(vector.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(vector)
        channel.write(buffer)
    }
}
```

So the file size is exactly `chunks × dims × 4` bytes. For 10,000 chunks at 768 dimensions that is about 30 MB — which is why this is binary and not JSON (JSON would be several times larger and take seconds to parse).

#### Atomic writes

Both files are written to `.tmp` names first and then moved into place. A crash mid-write can never leave a half-written `meta.json` next to a mismatched `vectors.bin`:

```kotlin
Files.move(vectorsTmp, directory.resolve(VECTORS_FILE), StandardCopyOption.REPLACE_EXISTING)
Files.move(metaTmp, directory.resolve(META_FILE), StandardCopyOption.REPLACE_EXISTING)
```

#### Periodic flushes

During a long build, the store is written every 500 chunks (`FLUSH_EVERY_CHUNKS`). If the IDE is closed halfway through, the next start loads the partial index and, because of hash-based reuse, only embeds the remainder.

#### Loading and validation

`IndexStore.load()` refuses (returns `null`, which triggers a rebuild) when anything is off: missing files, wrong `schemaVersion`, a different `embeddingModel` than expected, or a `vectors.bin` whose byte count does not equal `chunks × dims × 4`. There is no attempt to repair; a rebuild is cheap enough thanks to hashing.

### 5.9 Reporting progress to the UI

The service exposes its state as a `StateFlow`:

```kotlin
sealed interface IndexStatus {
    data object Idle : IndexStatus
    data class Building(val filesDone: Int, val filesTotal: Int, val chunks: Int) : IndexStatus
    data class Ready(val files: Int, val chunks: Int, val embeddingModel: String, val cappedNote: String?) : IndexStatus
    data class Error(val message: String) : IndexStatus
}
```

`statusDtoFlow()` combines this with the count of pending (not yet re-embedded) file changes into a plain, serializable `IndexStatusDto(enabled, phase, detail, unsynced)`. That DTO is the *only* thing about the index that ever crosses the RPC boundary to the UI:

```kotlin
@Rpc
interface IndexApi : RemoteApi<Unit> {
    suspend fun getStatusFlow(projectId: ProjectId): Flow<IndexStatusDto>
    suspend fun rebuild(projectId: ProjectId)
}
```

The chat header's colored sync indicator is a rendering of that flow. Clicking it calls `rebuild`.

---

## 6. Keeping the index fresh

A one-time build would go stale within minutes of editing. Three mechanisms keep it current, all in `ProjectIndexService`.

### VFS events → dirty set → debounced incremental pass

The service subscribes to IntelliJ's Virtual File System change bus. Each event just records a path:

```kotlin
private val dirtyPaths = ConcurrentHashMap.newKeySet<String>()
private val removedPaths = ConcurrentHashMap.newKeySet<String>()

private fun recordEvents(events: List<VFileEvent>) {
    for (event in events) {
        when (event) {
            is VFileContentChangeEvent -> dirtyPaths += event.file.path
            is VFileCreateEvent        -> event.file?.let { dirtyPaths += it.path }
            is VFileDeleteEvent        -> removedPaths += event.file.path
            is VFileMoveEvent          -> { removedPaths += event.oldPath; dirtyPaths += event.file.path }
            is VFilePropertyChangeEvent -> if (event.propertyName == VirtualFile.PROP_NAME) { /* rename: same as move */ }
        }
    }
    refreshPendingCount()
    changeTickle.tryEmit(Unit)
}
```

Recording is instant and cheap; the expensive work is **debounced by 3 seconds**, so a burst of saves during active typing becomes one embedding pass:

```kotlin
scope.launch {
    changeTickle.debounce(INCREMENTAL_DEBOUNCE_MS).collect {
        buildJob?.join()            // never race a full build
        runCatching { processIncremental() }.onFailure { ... }
    }
}
```

`processIncremental()` drains both sets and, for each dirty path: re-checks that the file is still in project content and not binary, reads it, hashes it, **skips it if the hash is unchanged** (a save with no real edits costs nothing), otherwise re-chunks and re-embeds just that file. Then it swaps in the updated map and persists.

The pending count is what makes the header indicator turn from "in sync" to "stale" the moment you edit, before the debounce fires.

### Failures are requeued, not dropped

If Ollama is down during an incremental pass, the paths are put *back* in the dirty set so the indicator stays honestly unsynced and the next event or a manual Rebuild retries them:

```kotlin
try {
    processChanges(removed, dirty)
} catch (e: Exception) {
    removedPaths.addAll(removed)
    dirtyPaths.addAll(dirty)
    refreshPendingCount()
    throw e
}
```

### Startup reconciliation

Files changed while the IDE was closed (a `git pull`, a branch switch, a colleague's commit) produce no VFS events. So after loading the index from disk on startup, `reconcileWithDisk()` walks the project once, hashes every file, and queues anything whose hash differs from the stored one — plus files that are new (no entry) or gone (entry but no file). It reuses the same dirty/removed sets and the same debounced pass, so the code path is identical to a live edit.

---

## 7. Phase 2 — answering a question

### 7.1 The entry point

When you send a chat message, the backend responder builds the system prompt. Part of that is the project-context block, assembled by `ProjectContextCollector.collect()`:

```kotlin
val context = ProjectContextCollector.getInstance(project)
    .collect(question = question, mentionPaths = attachments)
```

`collect()` does retrieval first (network, outside any read lock), then assembles the block:

```kotlin
suspend fun collect(question: String?, mentionPaths: List<String>, budgetChars: Int = 24_000): String {
    val retrieved = if (question != null) retrieve(question, mentionPaths) else emptyList()
    lastRetrieved = retrieved.map { it.first }     // for the context-files bar in the UI
    refreshContextFiles()
    val selection = captureSelection()
    return runReadAction { assemble(selection, mentionPaths, retrieved.map { it.second }, budgetChars) }
}
```

### 7.2 Embedding the question

`retrieve()` starts with several cheap bail-outs — indexing disabled, no index in memory, no model recorded — and then embeds the question **with the same model the index was built with**:

```kotlin
private suspend fun retrieve(question: String, mentionPaths: List<String>): List<Pair<ContextFileDto, String>> {
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
```

**Why the same model?** Vectors from different models live in different "spaces" — comparing them is meaningless, like comparing a temperature in Celsius with one in Fahrenheit without converting. `currentEmbeddingModel` is read from the loaded index's `meta.json`, so even if the user changed the embedding setting after the index was built, queries stay consistent with the stored vectors until a Rebuild switches everything over together.

The question is normalized too, so the dot products in the next step are true cosine similarities.

### 7.3 Scoring every chunk

[RetrievalSelector.kt](backend/src/main/kotlin/com/rizkybusiness/ai/assistant/index/RetrievalSelector.kt) is a brute-force scan: every stored vector gets a dot product with the query.

```kotlin
const val DEFAULT_TOP_K = 12
const val DEFAULT_SCORE_FLOOR = 0.30f

data class Hit(val path: String, val startLine: Int, val endLine: Int, val score: Float)

fun select(query: FloatArray, entries: Map<String, FileIndexEntry>, excludePaths: Set<String>,
           topK: Int = DEFAULT_TOP_K, scoreFloor: Float = DEFAULT_SCORE_FLOOR): List<Hit> {
    val scored = mutableListOf<Hit>()
    for ((path, entry) in entries) {
        if (path in excludePaths) continue
        entry.vectors.forEachIndexed { index, vector ->
            val score = VectorMath.dot(query, vector)
            if (score >= scoreFloor) {
                val meta = entry.chunks[index]
                scored += Hit(path, meta.startLine, meta.endLine, score)
            }
        }
    }
    return mergeAdjacent(scored.sortedByDescending { it.score }.take(topK))
}
```

Three knobs:

- **Exclusions.** Files that are open in the editor or `@`-mentioned are skipped entirely. They are already in the context in full; retrieving a fragment of them would waste budget on duplicate text.
- **Score floor `0.30`.** A question unrelated to the codebase ("write me a haiku") still produces *some* best-matching chunk. The floor drops weak matches so an off-topic question retrieves nothing rather than random noise.
- **Top-k `12`.** At most 12 chunks survive, sorted by score.

"Brute force over 25,000 vectors" sounds slow but is not: 25,000 chunks × 768 dims is about 19 million multiply-adds, which takes a few milliseconds. That is the reason the index has a chunk cap — it is what makes a vector database unnecessary.

### 7.4 Merging adjacent hits

Because chunks overlap, a relevant function often lights up two or three consecutive chunks of the same file. Sending them separately would show the model the same lines twice. `mergeAdjacent` combines hits from the same file whose line ranges touch or overlap, keeping the strongest score:

```kotlin
fun mergeAdjacent(hits: List<Hit>): List<Hit> {
    val merged = mutableListOf<Hit>()
    for (group in hits.groupBy { it.path }.values) {
        val sorted = group.sortedBy { it.startLine }
        var current = sorted.first()
        for (next in sorted.drop(1)) {
            current = if (next.startLine <= current.endLine + 1) {
                current.copy(endLine = maxOf(current.endLine, next.endLine), score = maxOf(current.score, next.score))
            } else {
                merged += current
                next
            }
        }
        merged += current
    }
    return merged.sortedByDescending { it.score }
}
```

Example. Suppose the top-k step produced these hits for `OllamaClient.kt`:

| Lines | Score |
|---|---|
| 1–40 | 0.61 |
| 33–70 | 0.55 |
| 120–160 | 0.42 |

Lines 33–70 start before line 41, so they merge with 1–40. Lines 120–160 do not touch 71, so they stay separate. Result:

| Lines | Score |
|---|---|
| 1–70 | 0.61 |
| 120–160 | 0.42 |

### 7.5 Rendering snippets

Remember that the index holds line numbers, not text. `renderHit` opens the file *now* and cuts out the merged range:

```kotlin
const val MAX_RETRIEVED_SNIPPET_CHARS = 4_000

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
```

Each snippet is capped at 4,000 characters, gets a header naming the file and line range, and is tagged `[retrieved]` so the model (and the system prompt, which explains the tag) knows where it came from. The `ContextFileDto` half of the pair is what the context-files bar in the UI shows.

The line numbers are clamped (`coerceIn`, `coerceAtMost`) because the file may have shrunk since it was indexed — an edit that is still inside the 3-second debounce window.

### 7.6 Fitting snippets into the context budget

Retrieved snippets are the **lowest-priority** source of context. `assemble()` fills the 24,000-character block in this order:

1. `@`-mentioned files (full content).
2. The current editor selection (up to 8,000 characters).
3. Open files and retrieved snippets, sharing whatever is left.

The split for step 3, from the source:

```kotlin
private const val RETRIEVED_BUDGET_SHARE = 0.4

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
```

#### Worked example

Budget 24,000. The user mentioned one 5,000-character file and has a 1,000-character selection.

| Step | Characters used | Notes |
|---|---|---|
| Mentioned file | 5,000 | |
| Selection | 1,000 | block is now 6,000 |
| `remaining` | 18,000 | 24,000 − 6,000 |
| `retrievedFloor` | 7,200 | 40% of 18,000, because there are hits |
| `openCap` | 16,800 | 6,000 + (18,000 − 7,200) |

Then two possible outcomes:

- **Open files are big.** They fill up to the cap of 16,800. Retrieval then appends snippets until the block would exceed 24,000 — it gets its guaranteed 7,200.
- **Open files are small** and only use 4,000, so the block sits at 10,000. Retrieval can now use up to 14,000. Open files' unused share spilled to retrieval.

Two asymmetries are worth knowing: retrieval's unused share does *not* flow back to open files (they were already capped), and when there are **no** retrieval hits the floor is `0` and open files take the entire remainder. Snippets are appended whole or not at all, in score order, so a lower-ranked snippet that does not fit is simply dropped.

### 7.7 What the model finally sees

Putting it together, the system prompt for our example question ends up shaped like this (real content abbreviated):

```
You are a concise coding assistant embedded in a JetBrains IDE. ...
Snippets labeled [retrieved] were found by semantic search of the project index.

Project context (each block is labeled with its source — mentioned, selection, open, or retrieved):
// File: C:/proj/backend/.../settings/AssistantSettings.kt [mentioned]
<full file>

// File: C:/proj/backend/.../ProjectIndexService.kt (lines 210-224) [user's current selection]
<selected lines>

// File: C:/proj/backend/.../ChatHeader.kt
<full open file>

// File: C:/proj/backend/.../ollama/OllamaClient.kt (lines 1-70) [retrieved]
<70 lines around mapConnectErrors>

// File: C:/proj/backend/.../ollama/OllamaClient.kt (lines 120-160) [retrieved]
<40 lines>
```

The user's question follows as the last chat message. Nothing matched the words "unreachable" or "model source" in `OllamaClient.kt`; the model saw those lines because the question's vector pointed the same way as the chunks containing `ConnectException` handling.

---

## 8. Failure policy

The guiding rule: **the index must never block chat.**

| Failure | What happens |
|---|---|
| No embedding model found | Build ends in `Error`, indicator turns red, notification shown. Chat works without retrieval. |
| Ollama unreachable during build | Same as above. A Rebuild retries; hashing means unchanged files are not re-embedded. |
| Ollama unreachable during an incremental pass | Paths go back into the dirty set; indicator stays "stale"; next event or Rebuild retries. |
| Ollama unreachable during a question | `retrieve()` catches, logs a warning to the host's `idea.log`, returns no snippets. The answer is generated from mentions/selection/open files only. |
| Corrupt or mismatched files on disk | `IndexStore.load()` returns `null`; the service rebuilds. |
| Repository too large | Files beyond 4,000 or chunks beyond 25,000 are skipped, and the status text says so ("indexed 4000 of 6231 files (capped)"). |
| File edited between index and question | Line ranges are clamped to the current file length; worst case a snippet is slightly off until the debounced re-index runs. |

Deleting the index folder by hand is always safe. It is rebuilt on the next project open.

## 9. Split mode: what runs where

In JetBrains Remote Development the IDE is split into a **host** (where the project files are) and a thin **client** (where you look at the UI). This plugin was built for that arrangement, and the index is the clearest example:

- **Host (backend module):** file enumeration, reading, hashing, chunking, the HTTP calls to Ollama, the on-disk store, VFS listening, retrieval, and prompt assembly. Every class in sections 5–7 lives here.
- **Client (frontend module):** the sync indicator and the Rebuild button. It receives `IndexStatusDto` values and sends `rebuild()`. It never sees a vector, a chunk, or a file's contents.

Consequences: Ollama must be reachable *from the host*, the index folder is on the host's disk, the indexing settings live under "Settings on Host", and index-related log lines are in the host's `idea.log`. In a local, non-split IDE both halves run in one process and behave identically.

## 10. Try it yourself

### Talk to Ollama directly

List models and spot which ones are embedding models by name:

```bash
curl http://localhost:11434/api/tags
```

Embed two sentences in one request and see the raw vectors:

```bash
curl http://localhost:11434/api/embed -d '{"model":"nomic-embed-text","input":["what happens when the model source is unreachable?","catch (e: ConnectException) { throw OllamaException(message(\"error.cannot.connect\", baseUrl), e) }"]}'
```

Pull an embedding model if you have none:

```bash
ollama pull nomic-embed-text
```

### Compute a similarity by hand

A self-contained Kotlin script that mirrors `VectorMath` and shows normalization making cosine equal to the dot product:

```kotlin
import kotlin.math.sqrt

fun normalize(v: FloatArray): FloatArray {
    val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
    return FloatArray(v.size) { v[it] / norm }
}

fun dot(a: FloatArray, b: FloatArray) = a.indices.sumOf { (a[it] * b[it]).toDouble() }

fun main() {
    val a = normalize(floatArrayOf(3f, 4f))
    val b = normalize(floatArrayOf(4f, 3f))
    val c = normalize(floatArrayOf(-4f, 3f))
    println("a·b = ${dot(a, b)}")   // 0.96
    println("a·c = ${dot(a, c)}")   // 0.0
}
```

Paste the two 768-number arrays from the `curl` above into this script to see a real similarity between a question and a code line.

### Run the unit tests

```bash
./gradlew :backend:test --tests "com.rizkybusiness.ai.assistant.index.*"
```

Reading `RetrievalSelectorTest` is a good way to see the floor, the exclusions, and the merging behave with tiny 2-dimensional vectors.

### Inspect an index on disk

Enable indexing in Settings, wait for the header indicator to go green, then open the folder from section 5.8. `meta.json` is human-readable. `vectors.bin` should be exactly `chunks × dims × 4` bytes — you can check that arithmetic against the numbers in `meta.json`.

## 11. Design decisions and FAQ

**Why no vector database?** The index is capped at 25,000 chunks. A brute-force scan of that many vectors is a few milliseconds, comfortably below the cost of the single HTTP call to embed the question. A database would add a dependency, a process, and a failure mode for no user-visible gain.

**Why store line ranges instead of chunk text?** Smaller files on disk, no stale second copy of the source, and the snippet the model sees is always the current text.

**Why chunks at all — why not one vector per file?** Embedding models have input limits, and one vector for a 500-line file blurs many topics into one average. Function-sized chunks match function-sized questions.

**Why overlap chunks?** So a declaration at a chunk boundary is not orphaned from its body. Merging adjacent hits (7.4) removes the duplication on the way out.

**Why normalize at write time?** It turns every similarity computation into a plain dot product and avoids recomputing vector lengths on every question.

**Why sequential embedding batches?** Ollama serves one request per model at a time; parallel requests just queue. Sequential batches of 16 keep memory bounded and progress reporting simple.

**Why does retrieval skip open files?** They are already in the context in full. Retrieving a fragment would spend budget on text the model can already see.

**What happens if I switch embedding models?** The next Rebuild detects the model mismatch in `meta.json`, discards the old vectors, and re-embeds everything with the new model. Until then, questions keep using the old model so they stay consistent with the stored vectors.

**Why a score floor of 0.30?** Empirically, code chunks unrelated to a question score below it with common embedding models, while genuinely related ones score well above. It is a constant in `RetrievalSelector` if you want to experiment.

**Does any code leave my machine?** Only to the Ollama endpoint you configured — the same place your chat prompts go. Vectors and the index stay on the host's disk.

## 12. Glossary

- **Embedding** — a vector of floats produced by a model from a text, such that similar meanings give similar vectors.
- **Vector / dims** — an array of floats; `dims` is its length (768 for `nomic-embed-text`).
- **Cosine similarity** — how aligned two vectors are, from −1 to 1. For normalized vectors it equals the dot product.
- **Normalize (L2)** — scale a vector so its length is 1.
- **Dot product** — sum of element-wise products of two vectors.
- **Chunk** — a slice of a file, at most 2,000 characters, with its 1-based start and end lines.
- **Overlap** — lines shared between consecutive chunks (up to 8 lines / 200 characters).
- **Top-k** — keep only the k best-scoring results (k = 12 here).
- **Score floor** — minimum similarity a chunk needs to be considered at all (0.30).
- **RAG** — Retrieval-Augmented Generation: fetch relevant text, then let the model generate with it in the prompt.
- **VFS** — IntelliJ's Virtual File System, which emits the change events that keep the index fresh.
- **Read action** — IntelliJ's read lock; required for any project-model or document access.
- **Host / client** — the two halves of a Remote Development session; this plugin's backend runs on the host, the UI on the client.
