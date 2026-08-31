package com.rizkybusiness.ai.assistant.ollama

import com.rizkybusiness.ai.assistant.ModularPluginBackendBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Failure talking to the model source; [message] is user-presentable and ends up in the chat. */
class OllamaException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

@Serializable
data class OllamaChatMessage(val role: String, val content: String)

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
)

@Serializable
internal data class OllamaChatChunk(
    val message: OllamaChatMessage? = null,
    val done: Boolean = false,
    @SerialName("done_reason") val doneReason: String? = null,
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
 * No request timeout on purpose: streamed responses and model cold-starts can take minutes.
 */
class OllamaClient(val baseUrl: String, val useProxy: Boolean = false) {

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
     * `done_reason` (e.g. "length") so callers can tell the user instead of hiding it.
     */
    fun chatStream(
        model: String,
        messages: List<OllamaChatMessage>,
        contextTokens: Int? = null,
        onDone: (String?) -> Unit = {},
    ): Flow<String> = flow {
        val request0 = OllamaChatRequest(
            model, messages,
            options = OllamaChatOptions(numCtx = contextTokens, numPredict = -1),
        )
        val body = json.encodeToString(OllamaChatRequest.serializer(), request0)
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/api/chat"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = mapConnectErrors { http.send(request, HttpResponse.BodyHandlers.ofLines()) }
        response.body().use { lines ->
            if (response.statusCode() != 200) {
                val firstLine = lines.findFirst().orElse("")
                throw httpError(response.statusCode(), firstLine, model)
            }
            for (line in lines.iterator()) {
                if (line.isBlank()) continue
                val chunk = json.decodeFromString<OllamaChatChunk>(line)
                chunk.error?.let {
                    throw OllamaException(ModularPluginBackendBundle.message("error.stream.aborted", it))
                }
                chunk.message?.content?.takeIf { it.isNotEmpty() }?.let { emit(it) }
                if (chunk.done) {
                    onDone(chunk.doneReason)
                    break
                }
            }
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
