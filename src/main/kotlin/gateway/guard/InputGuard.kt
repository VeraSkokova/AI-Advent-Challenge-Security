package gateway.guard

import gateway.config.GatewayConfig
import gateway.config.SecretPolicy
import gateway.model.ChatCompletionRequest
import gateway.model.ChatMessage
import gateway.model.GuardResult
import org.slf4j.LoggerFactory
import java.util.Base64

/**
 * Pre-LLM input guard that scans user messages for secrets, PII, and sensitive data.
 * Supports both BLOCK (reject request) and REDACT (mask and forward) policies.
 */
class InputGuard(private val config: GatewayConfig) {

    private val log = LoggerFactory.getLogger(InputGuard::class.java)

    // --- Secret patterns ---

    private data class SecretPattern(
        val name: String,
        val regex: Regex,
        val redactedLabel: String,
    )

    private val secretPatterns = listOf(
        SecretPattern("OpenAI API Key", Regex("""sk-[a-zA-Z0-9]{20,}"""), "[REDACTED_API_KEY]"),
        SecretPattern("GitHub PAT", Regex("""ghp_[a-zA-Z0-9]{36,}"""), "[REDACTED_GITHUB_TOKEN]"),
        SecretPattern("AWS Access Key", Regex("""AKIA[A-Z0-9]{16}"""), "[REDACTED_AWS_KEY]"),
        SecretPattern("Email", Regex("""[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}"""), "[REDACTED_EMAIL]"),
        SecretPattern(
            "Credit Card",
            Regex("""\b(?:\d[ -]*?){13,16}\b"""),
            "[REDACTED_CREDIT_CARD]",
        ),
        SecretPattern(
            "Phone Number",
            Regex("""(?:\+?1[-.\s]?)?\(?\d{3}\)?[-.\s]?\d{3}[-.\s]?\d{4}"""),
            "[REDACTED_PHONE]",
        ),
    )

    // Pattern to detect Base64-encoded strings (at least 20 chars, padded or not)
    private val base64Pattern = Regex("""[A-Za-z0-9+/]{20,}={0,2}""")

    // Split secret detection: catches concatenation tricks like "sk-" + "proj..." or 'sk-' + 'proj'
    private val splitSecretPattern = Regex(
        """["']?(sk-|ghp_|AKIA)["']?\s*[\+\.\s]+\s*["']?([a-zA-Z0-9]{4,})["']?""",
    )

    /**
     * Scan the entire request. Returns a [GuardResult] with violations and optionally sanitized request.
     */
    fun scan(request: ChatCompletionRequest): GuardResult {
        val allViolations = mutableListOf<String>()
        val sanitizedMessages = mutableListOf<ChatMessage>()

        for (message in request.messages) {
            val (violations, sanitized) = scanText(message.content)
            allViolations.addAll(violations)
            sanitizedMessages.add(message.copy(content = sanitized))
        }

        if (allViolations.isEmpty()) {
            return GuardResult(passed = true)
        }

        log.warn("InputGuard detected ${allViolations.size} violation(s): $allViolations")

        return when (config.inputPolicy) {
            SecretPolicy.BLOCK -> GuardResult(
                passed = false,
                violations = allViolations,
            )
            SecretPolicy.REDACT -> GuardResult(
                passed = true,
                violations = allViolations,
                sanitizedText = null, // We return sanitized messages via the request copy
            ).also {
                // Store sanitized request for caller to use
            }
        }.let { result ->
            if (config.inputPolicy == SecretPolicy.REDACT) {
                // Return result with sanitized request embedded
                result.copy(
                    sanitizedText = sanitizedMessages.joinToString("\n") { it.content },
                )
            } else {
                result
            }
        }
    }

    /**
     * Build a sanitized copy of the request with all secrets redacted.
     */
    fun redact(request: ChatCompletionRequest): ChatCompletionRequest {
        val sanitizedMessages = request.messages.map { message ->
            val (_, sanitized) = scanText(message.content)
            message.copy(content = sanitized)
        }
        return request.copy(messages = sanitizedMessages)
    }

    /**
     * Scan a single text block. Returns (violations, sanitizedText).
     */
    internal fun scanText(text: String): Pair<List<String>, String> {
        val violations = mutableListOf<String>()
        var sanitized = text

        // 1. Check for split secrets (concatenation tricks)
        splitSecretPattern.findAll(text).forEach { match ->
            val prefix = match.groupValues[1]
            val suffix = match.groupValues[2]
            val reconstructed = "$prefix$suffix"
            violations.add("Split secret detected: pattern '$prefix...' reassembled as '$reconstructed'")
            sanitized = sanitized.replace(match.value, "[REDACTED_SPLIT_SECRET]")
        }

        // 2. Check for Base64-encoded secrets
        base64Pattern.findAll(sanitized).forEach { match ->
            val candidate = match.value
            val decoded = tryBase64Decode(candidate)
            if (decoded != null) {
                val innerViolations = scanPlaintext(decoded)
                if (innerViolations.isNotEmpty()) {
                    violations.add("Base64-encoded secret found: ${innerViolations.joinToString()}")
                    sanitized = sanitized.replace(candidate, "[REDACTED_BASE64_SECRET]")
                }
            }
        }

        // 3. Direct pattern matching on plaintext
        for (pattern in secretPatterns) {
            pattern.regex.findAll(sanitized).forEach { match ->
                violations.add("${pattern.name} detected: '${mask(match.value)}'")
                sanitized = sanitized.replace(match.value, pattern.redactedLabel)
            }
        }

        return violations to sanitized
    }

    /** Scan plaintext for secret patterns (used for decoded Base64 payloads). */
    private fun scanPlaintext(text: String): List<String> {
        val violations = mutableListOf<String>()
        for (pattern in secretPatterns) {
            if (pattern.regex.containsMatchIn(text)) {
                violations.add("${pattern.name} in decoded payload")
            }
        }
        // Also check split secrets in decoded content
        if (splitSecretPattern.containsMatchIn(text)) {
            violations.add("Split secret in decoded payload")
        }
        return violations
    }

    /** Attempt Base64 decode; returns null if input is not valid Base64 or looks like normal text. */
    private fun tryBase64Decode(candidate: String): String? = try {
        val bytes = Base64.getDecoder().decode(candidate)
        val decoded = String(bytes, Charsets.UTF_8)
        // Only flag if decoded content looks like it contains structured data
        // (has printable ASCII and isn't just random bytes)
        if (decoded.all { it.code in 32..126 || it == '\n' || it == '\r' || it == '\t' }) {
            decoded
        } else {
            null
        }
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Mask a detected value for safe logging (show first 4 and last 2 chars). */
    private fun mask(value: String): String = when {
        value.length <= 8 -> "***"
        else -> "${value.take(4)}...${value.takeLast(2)}"
    }
}
