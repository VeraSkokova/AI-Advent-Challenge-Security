package gateway.proxy

import gateway.config.GatewayConfig
import gateway.guard.OutputGuard
import gateway.model.ChatCompletionRequest
import gateway.model.ChatCompletionResponse
import gateway.model.StreamChunk
import gateway.tracking.CostTracker
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Result of a proxy call — either a complete JSON response or a streaming Flow of SSE lines.
 * For streaming, [streamViolation] is set if the output guard terminated the stream early.
 */
sealed class ProxyResult {
    data class Complete(
        val response: ChatCompletionResponse,
        val outputGuardViolations: List<String> = emptyList(),
    ) : ProxyResult()

    data class Streaming(
        val sseFlow: Flow<String>,
    ) : ProxyResult()
}

/**
 * Forwards requests to the upstream LLM provider and applies output guard scanning.
 */
class LlmProxy(
    private val httpClient: HttpClient,
    private val config: GatewayConfig,
    private val outputGuard: OutputGuard,
    private val costTracker: CostTracker,
) {
    private val log = LoggerFactory.getLogger(LlmProxy::class.java)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Forward a non-streaming request to the upstream LLM.
     */
    suspend fun forwardSync(
        request: ChatCompletionRequest,
        authHeader: String?,
    ): ProxyResult.Complete {
        val requestBody = json.encodeToString(ChatCompletionRequest.serializer(), request)

        val response = httpClient.post("${config.upstreamUrl}/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            authHeader?.let { header(HttpHeaders.Authorization, it) }
            setBody(requestBody)
        }

        val responseBody = response.bodyAsText()
        val parsed = json.decodeFromString(ChatCompletionResponse.serializer(), responseBody)

        // Track usage from the response
        parsed.usage?.let { costTracker.recordUsage(it) }
            ?: run {
                // Fallback: count tokens locally
                val promptText = request.messages.joinToString("\n") { it.content }
                val completionText = parsed.choices.mapNotNull { it.message?.content }.joinToString("\n")
                costTracker.recordLocalUsage(promptText, completionText)
            }

        // Scan the output for dangerous content
        val fullOutput = parsed.choices.mapNotNull { it.message?.content }.joinToString("\n")
        val guardResult = outputGuard.scan(fullOutput)

        return ProxyResult.Complete(
            response = parsed,
            outputGuardViolations = guardResult.violations,
        )
    }

    /**
     * Forward a streaming request. Returns a Flow of SSE lines.
     *
     * The output guard uses a sliding window buffer to scan chunks on the fly.
     * If a violation is detected mid-stream, the flow emits a termination event
     * and completes early.
     */
    suspend fun forwardStreaming(
        request: ChatCompletionRequest,
        authHeader: String?,
    ): ProxyResult.Streaming {
        val requestBody = json.encodeToString(
            ChatCompletionRequest.serializer(),
            request.copy(stream = true),
        )

        val sseFlow = flow {
            val response = httpClient.preparePost("${config.upstreamUrl}/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                authHeader?.let { header(HttpHeaders.Authorization, it) }
                setBody(requestBody)
            }.execute { httpResponse ->
                val channel: ByteReadChannel = httpResponse.bodyAsChannel()
                val scanner = outputGuard.streamingScanner(config.streamingWindowSize)
                val completionBuffer = StringBuilder()

                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break

                    // SSE format: lines starting with "data: "
                    if (!line.startsWith("data: ")) {
                        // Forward empty lines and comments as-is (SSE protocol)
                        emit(line)
                        continue
                    }

                    val payload = line.removePrefix("data: ").trim()

                    // Stream termination signal
                    if (payload == "[DONE]") {
                        // Final flush of the output guard window
                        val flushResult = scanner.flush()
                        if (!flushResult.passed) {
                            log.warn("OutputGuard violation on stream flush: ${flushResult.violations}")
                            emit("data: ${buildTerminationEvent(flushResult.violations)}")
                            return@execute
                        }
                        emit(line)
                        break
                    }

                    // Parse the chunk to extract content for scanning
                    val chunk = try {
                        json.decodeFromString(StreamChunk.serializer(), payload)
                    } catch (e: Exception) {
                        log.debug("Failed to parse SSE chunk, forwarding raw: ${e.message}")
                        emit(line)
                        continue
                    }

                    // Extract delta content for the output guard
                    val deltaContent = chunk.choices.firstOrNull()?.delta?.content
                    if (deltaContent != null) {
                        completionBuffer.append(deltaContent)

                        // Sliding window scan: feed the new chunk into the scanner
                        val guardResult = scanner.addChunk(deltaContent)
                        if (!guardResult.passed) {
                            log.warn("OutputGuard violation mid-stream: ${guardResult.violations}")
                            emit("data: ${buildTerminationEvent(guardResult.violations)}")
                            return@execute // Terminate stream immediately
                        }
                    }

                    // Track usage if present in the final chunk
                    chunk.usage?.let { costTracker.recordUsage(it) }

                    emit(line)
                }

                // If no usage was reported in any chunk, count locally
                if (completionBuffer.isNotEmpty()) {
                    val promptText = request.messages.joinToString("\n") { it.content }
                    costTracker.recordLocalUsage(promptText, completionBuffer.toString())
                }
            }
        }

        return ProxyResult.Streaming(sseFlow = sseFlow)
    }

    /**
     * Build a JSON string representing a guard-terminated stream event.
     */
    private fun buildTerminationEvent(violations: List<String>): String {
        val message = "Response blocked by output guard: ${violations.joinToString("; ")}"
        return """{"error":{"message":"$message","type":"output_guard_violation","code":"content_filter"}}"""
    }
}
