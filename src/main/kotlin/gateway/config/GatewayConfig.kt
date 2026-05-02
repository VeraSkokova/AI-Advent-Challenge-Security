package gateway.config

data class GatewayConfig(
    /** URL of the upstream LLM provider (Ollama or OpenAI). */
    val upstreamUrl: String = "http://localhost:11434",
    /** Policy for handling detected secrets in input. */
    val inputPolicy: SecretPolicy = SecretPolicy.REDACT,
    /** Maximum requests per IP per window. */
    val rateLimitRequests: Int = 60,
    /** Rate limit window in seconds. */
    val rateLimitWindowSeconds: Long = 60,
    /** Port for the gateway server. */
    val serverPort: Int = 8080,
    /** Sliding window size (in chars) for streaming output guard. */
    val streamingWindowSize: Int = 512,
    /** Cost per 1K prompt tokens (USD). */
    val costPerPromptToken: Double = 0.0,
    /** Cost per 1K completion tokens (USD). */
    val costPerCompletionToken: Double = 0.0,
)

enum class SecretPolicy {
    /** Abort the request entirely with HTTP 403. */
    BLOCK,
    /** Mask detected secrets and forward the sanitized request. */
    REDACT,
}
