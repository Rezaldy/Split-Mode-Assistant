package com.rizkybusiness.ai.assistant.ollama

import com.rizkybusiness.ai.assistant.ModularPluginBackendBundle
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.io.UncheckedIOException
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional

/** Failure talking to the model source; [message] is user-presentable and ends up in the chat. */
class OllamaException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

@Serializable
data class OllamaChatMessage(
    val role: String,
    val content: String,
    val thinking: String? = null,
)

/** One streamed token: either reply content or the model's reasoning. */
data class OllamaStreamToken(val text: String, val isThinking: Boolean)

/**
 * End-of-stream metrics from the final `done:true` chunk. [promptTokens] counts only the
 * prompt tokens evaluated for THIS request — a server-side KV-cache hit on a repeated
 * prefix makes it undercount the true prompt size, so treat usage as a lower bound.
 */
data class OllamaDoneStats(
    val reason: String?,
    val promptTokens: Int,
    val replyTokens: Int,
)

@Serializable
internal data class OllamaChatOptions(
    @SerialName("num_ctx") val numCtx: Int? = null,
    @SerialName("num_predict") val numPredict: Int? = null,
)

@Serializable
internal data class OllamaChatRequest(
    val model: String,
    val messages: List<OllamaChatMessage>,
    val stream: Boolean = true,
    val options: OllamaChatOptions? = null,
    val think: Boolean? = null,
)

@Serializable
internal data class OllamaShowRequest(val model: String)

@Serializable
internal data class OllamaShowResponse(val capabilities: List<String> = emptyList())

@Serializable
internal data class OllamaChatChunk(
    val message: OllamaChatMessage? = null,
    val done: Boolean = false,
    @SerialName("done_reason") val doneReason: String? = null,
    @SerialName("prompt_eval_count") val promptEvalCount: Int? = null,
    @SerialName("eval_count") val evalCount: Int? = null,
    val error: String? = null,
)

@Serializable
internal data class OllamaTagsResponse(val models: List<OllamaTagModel> = emptyList())

@Serializable
internal data class OllamaTagModel(val name: String)

@Serializable
internal data class OllamaErrorResponse(val error: String? = null)

@Serializable
internal data class OllamaEmbedRequest(val model: String, val input: List<String>)

@Serializable
internal data class OllamaEmbedResponse(val embeddings: List<List<Float>> = emptyList())

/**
 * Client for one Ollama-compatible model source. JDK HttpClient + kotlinx.serialization only.
 *
 * No TOTAL request timeout on purpose: streamed responses and model cold-starts can take
 * minutes. Streams instead have an idle watchdog — generous before the first chunk (cold
 * model load), short between chunks (a healthy generation emits tokens continuously) —
 * because a source that stalls with the connection open would otherwise hang the chat
 * forever with no error anywhere (observed in the field as "reply silently cut off").
 */
class OllamaClient(val baseUrl: String, val useProxy: Boolean = false) {

    companion object {
        /** Max wait for the first stream chunk: covers cold-loading a large model. */
        const val FIRST_CHUNK_TIMEOUT_MS = 600_000L

        /** Max silence between chunks mid-generation before the stream counts as stalled. */
        const val STREAM_IDLE_TIMEOUT_MS = 120_000L
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .apply {
            // Direct by default: a model source is usually local/LAN infrastructure, and
            // a backend JVM carrying corporate proxy settings detours the traffic into a
            // web proxy that answers 403 (curl-works-plugin-doesn't). The setting flips
            // this to honor the IDE's proxy configuration via the default selector.
            if (!useProxy) proxy(HttpClient.Builder.NO_PROXY)
        }
        .build()

    suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/api/tags")).GET().build()
        val response = mapConnectErrors { http.send(request, HttpResponse.BodyHandlers.ofString()) }
        if (response.statusCode() != 200) throw httpError(response.statusCode(), response.body())
        json.decodeFromString<OllamaTagsResponse>(response.body()).models.map { it.name }
    }

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
            throw OllamaException(
                ModularPluginBackendBundle.message(
                    "error.generic",
                    "embed returned ${parsed.embeddings.size} vectors for ${inputs.size} inputs",
                )
            )
        }
        parsed.embeddings.map { it.toFloatArray() }
    }

    /**
     * Streams assistant tokens from `/api/chat` (NDJSON, one JSON object per line).
     *
     * [contextTokens] overrides Ollama's small default `num_ctx` — without it, large
     * prompts (project context!) silently truncate replies. `num_predict` is pinned to
     * unlimited; when the model still stops on a limit, [onDone] receives the server's
     * `done_reason` (e.g. "length") plus token counts so callers can tell the user
     * instead of hiding it.
     */
    /** Capabilities from `/api/show` (e.g. "thinking", "tools"); empty when the call fails. */
    suspend fun modelCapabilities(model: String): List<String> = withContext(Dispatchers.IO) {
        val body = json.encodeToString(OllamaShowRequest.serializer(), OllamaShowRequest(model))
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/api/show"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = mapConnectErrors { http.send(request, HttpResponse.BodyHandlers.ofString()) }
        if (response.statusCode() != 200) return@withContext emptyList()
        json.decodeFromString<OllamaShowResponse>(response.body()).capabilities
    }

    fun chatStream(
        model: String,
        messages: List<OllamaChatMessage>,
        contextTokens: Int? = null,
        requestThinking: Boolean = false,
        onDone: (OllamaDoneStats) -> Unit = {},
    ): Flow<OllamaStreamToken> = flow {
        val request0 = OllamaChatRequest(
            model, messages,
            options = OllamaChatOptions(numCtx = contextTokens, numPredict = -1),
            think = if (requestThinking) true else null,
        )
        val body = json.encodeToString(OllamaChatRequest.serializer(), request0)
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/api/chat"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = mapConnectErrors { http.send(request, HttpResponse.BodyHandlers.ofLines()) }
        var sawDone = false
        var chunkCount = 0
        // mapConnectErrors also covers mid-stream IOExceptions -> "stream interrupted" bubble.
        mapConnectErrors { response.body().use { lines ->
            if (response.statusCode() != 200) {
                val firstLine = lines.findFirst().orElse("")
                throw httpError(response.statusCode(), firstLine, model)
            }
            val iterator = lines.iterator()
            var idleTimeoutMs = FIRST_CHUNK_TIMEOUT_MS
            while (true) {
                // The JDK line stream only offers blocking reads; runInterruptible turns the
                // watchdog timeout's cancellation into a thread interrupt that unblocks it.
                val next = withTimeoutOrNull(idleTimeoutMs) {
                    runInterruptible(Dispatchers.IO) {
                        try {
                            if (iterator.hasNext()) Optional.of(iterator.next()) else Optional.empty()
                        } catch (e: UncheckedIOException) {
                            throw e.cause ?: e
                        }
                    }
                }
                if (next == null) {
                    thisLogger().warn(
                        "Chat stream for '$model' stalled: no data for ${idleTimeoutMs / 1000}s " +
                            "after $chunkCount chunks"
                    )
                    throw OllamaException(
                        ModularPluginBackendBundle.message("error.stream.stalled", idleTimeoutMs / 1000)
                    )
                }
                if (next.isEmpty) break
                val line = next.get()
                idleTimeoutMs = STREAM_IDLE_TIMEOUT_MS
                if (line.isBlank()) continue
                val chunk = json.decodeFromString<OllamaChatChunk>(line)
                chunkCount++
                chunk.error?.let {
                    throw OllamaException(ModularPluginBackendBundle.message("error.stream.aborted", it))
                }
                chunk.message?.thinking?.takeIf { it.isNotEmpty() }?.let {
                    emit(OllamaStreamToken(it, isThinking = true))
                }
                chunk.message?.content?.takeIf { it.isNotEmpty() }?.let {
                    emit(OllamaStreamToken(it, isThinking = false))
                }
                if (chunk.done) {
                    sawDone = true
                    val stats = OllamaDoneStats(
                        reason = chunk.doneReason,
                        promptTokens = chunk.promptEvalCount ?: 0,
                        replyTokens = chunk.evalCount ?: 0,
                    )
                    // Every stream end is logged: cut-off reports keep coming in with no
                    // visible error, and this line is what tells apart "model stopped"
                    // from "limit hit" after the fact (host idea.log).
                    thisLogger().info(
                        "Chat stream done: model='$model' reason=${stats.reason} " +
                            "prompt=${stats.promptTokens} reply=${stats.replyTokens} num_ctx=$contextTokens"
                    )
                    onDone(stats)
                    break
                }
            }
        } }
        // Ollama always terminates a healthy stream with done:true. EOF without it means
        // something between us and the model (proxy/gateway timeout, server death) cut
        // the connection — silently accepting the partial reply hides real infrastructure
        // problems (observed in the field: a corporate gateway chopping slow streams).
        if (!sawDone) {
            thisLogger().warn("Chat stream for '$model' ended without done:true after $chunkCount chunks")
            throw OllamaException(ModularPluginBackendBundle.message("error.stream.incomplete"))
        }
    }.flowOn(Dispatchers.IO)

    private inline fun <T> mapConnectErrors(block: () -> T): T = try {
        block()
    } catch (e: ConnectException) {
        throw OllamaException(ModularPluginBackendBundle.message("error.cannot.connect", baseUrl), e)
    } catch (e: HttpConnectTimeoutException) {
        throw OllamaException(ModularPluginBackendBundle.message("error.cannot.connect", baseUrl), e)
    } catch (e: IOException) {
        throw OllamaException(
            ModularPluginBackendBundle.message("error.stream.aborted", e.message ?: e.javaClass.simpleName), e,
        )
    }

    private fun httpError(status: Int, body: String, model: String? = null): OllamaException {
        val detail = runCatching { json.decodeFromString<OllamaErrorResponse>(body).error }.getOrNull()
        return if (status == 404 && model != null && detail != null && "not found" in detail) {
            OllamaException(ModularPluginBackendBundle.message("error.model.not.found", model, baseUrl))
        } else {
            OllamaException(
                ModularPluginBackendBundle.message("error.generic.at", detail ?: "HTTP $status", baseUrl)
            )
        }
    }
}
