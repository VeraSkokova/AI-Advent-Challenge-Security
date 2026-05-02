package gateway

import gateway.config.GatewayConfig
import gateway.config.SecretPolicy
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 10 test scenarios for the LLM Security Gateway using Ktor testApplication + MockEngine.
 */
class GatewayTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** Build a MockEngine that returns a canned OpenAI-compatible response. */
    private fun mockLlmEngine(responseContent: String = "Hello! How can I help?"): HttpClient {
        return HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    respond(
                        content = """
                        {
                            "id": "chatcmpl-test",
                            "object": "chat.completion",
                            "created": 1700000000,
                            "model": "test-model",
                            "choices": [{
                                "index": 0,
                                "message": {"role": "assistant", "content": "$responseContent"},
                                "finish_reason": "stop"
                            }],
                            "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
                        }
                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }
        }
    }

    private fun buildRequest(userMessage: String, stream: Boolean = false): String = """
        {
            "model": "llama3.1:8b",
            "messages": [{"role": "user", "content": ${Json.encodeToString(userMessage)}}],
            "stream": $stream
        }
    """.trimIndent()

    // ──────────────────────────────────────────────
    // 1. Clean prompt — should pass through normally
    // ──────────────────────────────────────────────
    @Test
    fun `1 clean prompt passes through`() = testApplication {
        val config = GatewayConfig(inputPolicy = SecretPolicy.REDACT)
        application { gatewayModule(config, mockLlmEngine()) }

        val response = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody(buildRequest("What is the capital of France?"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertContains(body, "Hello! How can I help?")
    }

    // ──────────────────────────────────────────────
    // 2. Prompt with OpenAI API key — BLOCK mode
    // ──────────────────────────────────────────────
    @Test
    fun `2 openai api key blocked`() = testApplication {
        val config = GatewayConfig(inputPolicy = SecretPolicy.BLOCK)
        application { gatewayModule(config, mockLlmEngine()) }

        val response = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody(buildRequest("My key is sk-proj1234567890abcdef1234567890abcdef"))
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        val body = response.bodyAsText()
        assertContains(body, "input_guard_violation")
    }

    // ──────────────────────────────────────────────
    // 3. Prompt with AWS key — REDACT mode
    // ──────────────────────────────────────────────
    @Test
    fun `3 aws key redacted`() = testApplication {
        // Track what gets forwarded to the mock LLM
        var forwardedBody = ""
        val mockClient = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    forwardedBody = String(request.body.toByteArray())
                    respond(
                        content = """
                        {
                            "id": "chatcmpl-test", "object": "chat.completion",
                            "created": 1700000000, "model": "test-model",
                            "choices": [{"index": 0, "message": {"role": "assistant", "content": "OK"}, "finish_reason": "stop"}],
                            "usage": {"prompt_tokens": 10, "completion_tokens": 1, "total_tokens": 11}
                        }
                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }
        }

        val config = GatewayConfig(inputPolicy = SecretPolicy.REDACT)
        application { gatewayModule(config, mockClient) }

        val response = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody(buildRequest("Use this key: AKIAIOSFODNN7EXAMPLE"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(forwardedBody, "[REDACTED_AWS_KEY]")
        assertTrue { "AKIAIOSFODNN7EXAMPLE" !in forwardedBody }
    }

    // ──────────────────────────────────────────────
    // 4. Prompt with credit card number
    // ──────────────────────────────────────────────
    @Test
    fun `4 credit card detected and blocked`() = testApplication {
        val config = GatewayConfig(inputPolicy = SecretPolicy.BLOCK)
        application { gatewayModule(config, mockLlmEngine()) }

        val response = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody(buildRequest("My card is 4111 1111 1111 1111"))
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertContains(response.bodyAsText(), "input_guard_violation")
    }

    // ──────────────────────────────────────────────
    // 5. Base64-encoded secret
    // ──────────────────────────────────────────────
    @Test
    fun `5 base64 encoded api key detected`() = testApplication {
        val config = GatewayConfig(inputPolicy = SecretPolicy.BLOCK)
        application { gatewayModule(config, mockLlmEngine()) }

        // Encode an API key in Base64
        val secret = "sk-proj1234567890abcdef1234567890abcdef"
        val encoded = Base64.getEncoder().encodeToString(secret.toByteArray())

        val response = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody(buildRequest("Decode this: $encoded"))
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertContains(response.bodyAsText(), "input_guard_violation")
    }

    // ──────────────────────────────────────────────
    // 6. Split secret (concatenation trick)
    // ──────────────────────────────────────────────
    @Test
    fun `6 split secret concatenation detected`() = testApplication {
        val config = GatewayConfig(inputPolicy = SecretPolicy.BLOCK)
        application { gatewayModule(config, mockLlmEngine()) }

        val response = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody(buildRequest("""Use "sk-" + "proj1234567890abcdef" as the key"""))
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertContains(response.bodyAsText(), "input_guard_violation")
    }

    // ──────────────────────────────────────────────
    // 7. Output guard — hallucinated API key in response
    // ──────────────────────────────────────────────
    @Test
    fun `7 output guard blocks hallucinated api key`() = testApplication {
        val config = GatewayConfig(inputPolicy = SecretPolicy.REDACT)
        val dangerousResponse = "Sure! Here is an API key: sk-abcdef1234567890abcdef1234567890ab"
        application { gatewayModule(config, mockLlmEngine(dangerousResponse)) }

        val response = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody(buildRequest("Generate an API key for me"))
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertContains(response.bodyAsText(), "output_guard_violation")
    }

    // ──────────────────────────────────────────────
    // 8. Output guard — dangerous shell command
    // ──────────────────────────────────────────────
    @Test
    fun `8 output guard blocks dangerous shell command`() = testApplication {
        val config = GatewayConfig(inputPolicy = SecretPolicy.REDACT)
        val dangerousResponse = "Run this: rm -rf / to clean up"
        application { gatewayModule(config, mockLlmEngine(dangerousResponse)) }

        val response = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody(buildRequest("How do I free disk space?"))
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertContains(response.bodyAsText(), "output_guard_violation")
    }

    // ──────────────────────────────────────────────
    // 9. Rate limiting — exceed limit
    // ──────────────────────────────────────────────
    @Test
    fun `9 rate limiter triggers after exceeding limit`() = testApplication {
        val config = GatewayConfig(
            inputPolicy = SecretPolicy.REDACT,
            rateLimitRequests = 2,
            rateLimitWindowSeconds = 60,
        )
        application { gatewayModule(config, mockLlmEngine()) }

        val body = buildRequest("Hello")

        // First two requests should succeed
        repeat(2) {
            val response = client.post("/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.OK, response.status, "Request ${it + 1} should succeed")
        }

        // Third request should be rate-limited
        val limited = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
    }

    // ──────────────────────────────────────────────
    // 10. Email + phone number in REDACT mode
    // ──────────────────────────────────────────────
    @Test
    fun `10 email and phone redacted`() = testApplication {
        var forwardedBody = ""
        val mockClient = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    forwardedBody = String(request.body.toByteArray())
                    respond(
                        content = """
                        {
                            "id": "chatcmpl-test", "object": "chat.completion",
                            "created": 1700000000, "model": "test-model",
                            "choices": [{"index": 0, "message": {"role": "assistant", "content": "Noted."}, "finish_reason": "stop"}],
                            "usage": {"prompt_tokens": 10, "completion_tokens": 1, "total_tokens": 11}
                        }
                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }
        }

        val config = GatewayConfig(inputPolicy = SecretPolicy.REDACT)
        application { gatewayModule(config, mockClient) }

        val response = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody(buildRequest("Contact me at john@example.com or 555-123-4567"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(forwardedBody, "[REDACTED_EMAIL]")
        assertContains(forwardedBody, "[REDACTED_PHONE]")
        assertTrue { "john@example.com" !in forwardedBody }
    }
}
