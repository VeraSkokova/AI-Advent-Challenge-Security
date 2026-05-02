package gateway

import gateway.config.GatewayConfig
import gateway.config.SecretPolicy
import gateway.guard.InputGuard
import gateway.guard.OutputGuard
import gateway.model.ChatCompletionRequest
import gateway.model.ErrorDetail
import gateway.model.ErrorResponse
import gateway.proxy.LlmProxy
import gateway.proxy.ProxyResult
import gateway.tracking.CostTracker
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

private val gatewayLog = LoggerFactory.getLogger("GatewayServer")

private val jsonCodec = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
}

/**
 * Creates the Ktor Application module for the LLM Security Gateway.
 * Extracted as a function so it can be used both by the real server and testApplication.
 */
fun Application.gatewayModule(
    config: GatewayConfig = GatewayConfig(),
    httpClient: HttpClient? = null,
) {
    val client = httpClient ?: HttpClient(CIO) {
        engine {
            requestTimeout = 120_000
        }
    }

    val inputGuard = InputGuard(config)
    val outputGuard = OutputGuard()
    val costTracker = CostTracker(config)
    val llmProxy = LlmProxy(client, config, outputGuard, costTracker)

    install(ContentNegotiation) {
        json(jsonCodec)
    }

    install(CallLogging)

    install(RateLimit) {
        register {
            rateLimiter(limit = config.rateLimitRequests, refillPeriod = config.rateLimitWindowSeconds.seconds)
            requestKey { call ->
                call.request.local.remoteAddress
            }
        }
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            gatewayLog.error("Unhandled exception", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(ErrorDetail(message = cause.message ?: "Internal error", type = "server_error")),
            )
        }
    }

    routing {
        rateLimit {
            post("/v1/chat/completions") {
                handleChatCompletion(call, config, inputGuard, llmProxy, costTracker)
            }
        }

        get("/health") {
            call.respond(mapOf("status" to "ok"))
        }

        get("/stats") {
            call.respond(costTracker.stats())
        }
    }
}

private suspend fun handleChatCompletion(
    call: ApplicationCall,
    config: GatewayConfig,
    inputGuard: InputGuard,
    llmProxy: LlmProxy,
    costTracker: CostTracker,
) {
    val rawBody = call.receiveText()
    gatewayLog.info("Incoming request: ${rawBody.take(500)}")

    val request = try {
        jsonCodec.decodeFromString(ChatCompletionRequest.serializer(), rawBody)
    } catch (e: Exception) {
        gatewayLog.warn("Failed to parse request: ${e.message}")
        call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse(ErrorDetail(message = "Invalid request: ${e.message}", type = "invalid_request")),
        )
        return
    }

    // --- Input Guard ---
    val guardResult = inputGuard.scan(request)
    if (!guardResult.passed) {
        gatewayLog.warn("InputGuard BLOCKED request: ${guardResult.violations}")
        call.respond(
            HttpStatusCode.Forbidden,
            ErrorResponse(
                ErrorDetail(
                    message = "Request blocked by input guard: ${guardResult.violations.joinToString("; ")}",
                    type = "input_guard_violation",
                    code = "content_filter",
                ),
            ),
        )
        return
    }

    if (guardResult.violations.isNotEmpty()) {
        gatewayLog.info("InputGuard REDACTED ${guardResult.violations.size} item(s)")
    }

    // Use redacted request if policy is REDACT and violations were found
    val forwardRequest = if (config.inputPolicy == SecretPolicy.REDACT && guardResult.violations.isNotEmpty()) {
        inputGuard.redact(request)
    } else {
        request
    }

    val authHeader = call.request.header(HttpHeaders.Authorization)

    // --- Forward to LLM ---
    if (request.stream) {
        handleStreaming(call, llmProxy, forwardRequest, authHeader)
    } else {
        handleSync(call, llmProxy, forwardRequest, authHeader)
    }
}

private suspend fun handleSync(
    call: ApplicationCall,
    llmProxy: LlmProxy,
    request: ChatCompletionRequest,
    authHeader: String?,
) {
    val result = llmProxy.forwardSync(request, authHeader)

    if (result.outputGuardViolations.isNotEmpty()) {
        gatewayLog.warn("OutputGuard flagged response: ${result.outputGuardViolations}")
        call.respond(
            HttpStatusCode.Forbidden,
            ErrorResponse(
                ErrorDetail(
                    message = "Response blocked by output guard: ${result.outputGuardViolations.joinToString("; ")}",
                    type = "output_guard_violation",
                    code = "content_filter",
                ),
            ),
        )
        return
    }

    val responseJson = jsonCodec.encodeToString(
        gateway.model.ChatCompletionResponse.serializer(),
        result.response,
    )
    call.respondText(responseJson, ContentType.Application.Json)
}

private suspend fun handleStreaming(
    call: ApplicationCall,
    llmProxy: LlmProxy,
    request: ChatCompletionRequest,
    authHeader: String?,
) {
    val result = llmProxy.forwardStreaming(request, authHeader)

    call.respondTextWriter(ContentType.Text.EventStream) {
        result.sseFlow.onEach { line ->
            write(line)
            write("\n")
            flush()
        }.collect()
    }
}

fun main() {
    val config = GatewayConfig(
        upstreamUrl = System.getenv("LLM_UPSTREAM_URL") ?: "http://localhost:11434",
        inputPolicy = when (System.getenv("INPUT_POLICY")?.uppercase()) {
            "BLOCK" -> SecretPolicy.BLOCK
            else -> SecretPolicy.REDACT
        },
        rateLimitRequests = System.getenv("RATE_LIMIT")?.toIntOrNull() ?: 60,
        serverPort = System.getenv("SERVER_PORT")?.toIntOrNull() ?: 8080,
    )

    gatewayLog.info("Starting LLM Security Gateway on port ${config.serverPort}")
    gatewayLog.info("Upstream: ${config.upstreamUrl}, Policy: ${config.inputPolicy}")

    embeddedServer(Netty, port = config.serverPort) {
        gatewayModule(config)
    }.start(wait = true)
}
