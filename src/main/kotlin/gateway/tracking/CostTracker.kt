package gateway.tracking

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.EncodingType
import gateway.config.GatewayConfig
import gateway.model.Usage
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks token usage and estimated cost across all requests.
 * Uses the LLM's reported [Usage] when available, falling back to
 * local token counting via JTokkit (cl100k_base encoding, compatible with GPT-3.5/4).
 */
class CostTracker(private val config: GatewayConfig) {

    private val log = LoggerFactory.getLogger(CostTracker::class.java)

    private val totalPromptTokens = AtomicLong(0)
    private val totalCompletionTokens = AtomicLong(0)
    private val totalRequests = AtomicLong(0)

    // JTokkit encoder for local fallback token counting
    private val encoding by lazy {
        val registry = Encodings.newDefaultEncodingRegistry()
        registry.getEncoding(EncodingType.CL100K_BASE)
    }

    /**
     * Record usage from the LLM response's `usage` field.
     */
    fun recordUsage(usage: Usage) {
        totalPromptTokens.addAndGet(usage.promptTokens.toLong())
        totalCompletionTokens.addAndGet(usage.completionTokens.toLong())
        totalRequests.incrementAndGet()

        log.info(
            "Usage recorded: prompt={}, completion={}, total={}. " +
                "Cumulative: prompt={}, completion={}, requests={}",
            usage.promptTokens,
            usage.completionTokens,
            usage.totalTokens,
            totalPromptTokens.get(),
            totalCompletionTokens.get(),
            totalRequests.get(),
        )
    }

    /**
     * Fallback: count tokens locally when the LLM response doesn't include usage info
     * (common during streaming).
     */
    fun countTokens(text: String): Int = encoding.countTokens(text)

    /**
     * Record usage using local token counts (fallback for streaming).
     */
    fun recordLocalUsage(promptText: String, completionText: String) {
        val promptTokens = countTokens(promptText)
        val completionTokens = countTokens(completionText)
        recordUsage(
            Usage(
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                totalTokens = promptTokens + completionTokens,
            ),
        )
    }

    /**
     * Calculate estimated cost based on configured per-token pricing.
     */
    fun estimatedCostUsd(): Double {
        val promptCost = (totalPromptTokens.get() / 1000.0) * config.costPerPromptToken
        val completionCost = (totalCompletionTokens.get() / 1000.0) * config.costPerCompletionToken
        return promptCost + completionCost
    }

    fun stats(): Map<String, Any> = mapOf(
        "totalRequests" to totalRequests.get(),
        "totalPromptTokens" to totalPromptTokens.get(),
        "totalCompletionTokens" to totalCompletionTokens.get(),
        "estimatedCostUsd" to estimatedCostUsd(),
    )
}
