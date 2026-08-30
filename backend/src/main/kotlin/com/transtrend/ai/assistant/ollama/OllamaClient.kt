package com.transtrend.ai.assistant.ollama

import com.transtrend.ai.assistant.ModularPluginBackendBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
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
internal data class OllamaChatRequest(
    val model: String,
    val messages: List<OllamaChatMessage>,
    val stream: Boolean = true,
)

@Serializable
internal data class OllamaChatChunk(
    val message: OllamaChatMessage? = null,
    val done: Boolean = false,
    val error: String? = null,
)

@Serializable
internal data class OllamaTagsResponse(val models: List<OllamaTagModel> = emptyList())

@Serializable
internal data class OllamaTagModel(val name: String)

@Serializable
internal data class OllamaErrorResponse(val error: String? = null)

/**
 * Client for one Ollama-compatible model source. JDK HttpClient + kotlinx.serialization only.
 *
 * No request timeout on purpose: streamed responses and model cold-starts can take minutes.
 */
class OllamaClient(val baseUrl: String) {

    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/api/tags")).GET().build()
        val response = mapConnectErrors { http.send(request, HttpResponse.BodyHandlers.ofString()) }
        if (response.statusCode() != 200) throw httpError(response.statusCode(), response.body())
        json.decodeFromString<OllamaTagsResponse>(response.body()).models.map { it.name }
    }

    /** Streams assistant tokens from `/api/chat` (NDJSON, one JSON object per line). */
    fun chatStream(model: String, messages: List<OllamaChatMessage>): Flow<String> = flow {
        val body = json.encodeToString(OllamaChatRequest.serializer(), OllamaChatRequest(model, messages))
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
                if (chunk.done) break
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
            OllamaException(ModularPluginBackendBundle.message("error.generic", detail ?: "HTTP $status"))
        }
    }
}
