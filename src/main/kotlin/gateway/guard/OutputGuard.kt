package gateway.guard

import gateway.model.GuardResult
import org.slf4j.LoggerFactory

/**
 * Post-LLM output guard that scans model responses for dangerous content:
 * - Hallucinated secrets (API keys, tokens)
 * - Suspicious URLs (raw IPs, unusual ports)
 * - Dangerous shell commands
 * - System prompt leak attempts
 */
class OutputGuard {

    private val log = LoggerFactory.getLogger(OutputGuard::class.java)

    private data class OutputPattern(
        val name: String,
        val regex: Regex,
    )

    companion object {
        /**
         * Whitespace class that includes regular spaces, tabs, newlines,
         * and Unicode non-breaking spaces (U+00A0) that LLMs often emit.
         */
        private const val WS = """[\s\u00A0]"""

        /**
         * Non-word boundary: matches start-of-string OR any character that is
         * not alphanumeric/underscore. Replaces \b which fails when the
         * preceding character is punctuation like ' or ".
         */
        private const val LB = """(?:^|[^\w])"""

        /** Trailing boundary: end-of-string OR non-word character. */
        private const val RB = """(?:[^\w]|$)"""
    }

    private val dangerousPatterns = listOf(
        // Hallucinated secrets — model might generate fake but realistic-looking keys
        OutputPattern("API Key", Regex("""sk-[a-zA-Z0-9]{20,}""")),
        OutputPattern("GitHub Token", Regex("""ghp_[a-zA-Z0-9]{36,}""")),
        OutputPattern("AWS Key", Regex("""AKIA[A-Z0-9]{16}""")),

        // Suspicious URLs (raw IP addresses, high ports often used for C2)
        OutputPattern(
            "Suspicious URL",
            Regex("""https?://\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}(:\d+)?(/\S*)?"""),
        ),

        // Dangerous shell commands — boundaries handle surrounding punctuation/NBSP
        OutputPattern("Shell: rm -rf", Regex("""${LB}rm${WS}+-rf${WS}+/""", RegexOption.IGNORE_CASE)),
        OutputPattern("Shell: curl pipe", Regex("""${LB}curl${WS}+\S+${WS}*\|${WS}*(?:bash|sh|zsh)""", RegexOption.IGNORE_CASE)),
        OutputPattern("Shell: wget execute", Regex("""${LB}wget${WS}+\S+.*&&${WS}*(?:chmod|bash|sh)""", RegexOption.IGNORE_CASE)),
        OutputPattern("Shell: curl to file", Regex("""${LB}curl${WS}+-[oO]${WS}""", RegexOption.IGNORE_CASE)),
        OutputPattern("Shell: curl command", Regex("""${LB}curl${WS}+\S""", RegexOption.IGNORE_CASE)),
        OutputPattern("Shell: wget", Regex("""${LB}wget${WS}+https?://\S+""", RegexOption.IGNORE_CASE)),

        // System prompt leak indicators
        OutputPattern(
            "Prompt Leak",
            Regex(
                """(?:system${WS}*prompt|you${WS}+are${WS}+a|your${WS}+instructions|<<SYS>>|<\|im_start\|>system)""",
                RegexOption.IGNORE_CASE,
            ),
        ),
    )

    /**
     * Scan the complete (non-streaming) LLM response text.
     */
    fun scan(text: String): GuardResult {
        val violations = mutableListOf<String>()

        for (pattern in dangerousPatterns) {
            pattern.regex.findAll(text).forEach { match ->
                violations.add("${pattern.name}: '${match.value.take(80)}'")
            }
        }

        if (violations.isNotEmpty()) {
            log.warn("OutputGuard detected ${violations.size} violation(s): $violations")
        }

        return GuardResult(
            passed = violations.isEmpty(),
            violations = violations,
        )
    }

    /**
     * Sliding window scanner for streaming chunks.
     *
     * How the sliding window works:
     * ─────────────────────────────
     * As SSE chunks arrive, each chunk's content (often just a few tokens) is appended
     * to an internal buffer. The buffer retains up to [windowSize] characters.
     * After each append, the ENTIRE buffer is scanned against all patterns.
     *
     * Why a window and not per-chunk scanning?
     * A dangerous pattern like "sk-proj1234567890abcdef1234" could be split across
     * two or more SSE chunks (e.g., chunk1="sk-proj12345", chunk2="67890abcdef1234").
     * Per-chunk scanning would miss this. The sliding window ensures we always have
     * enough context to catch cross-chunk patterns.
     *
     * When the buffer exceeds [windowSize], we trim from the front — but we keep
     * an overlap (half the window) to avoid losing partial matches at the boundary.
     *
     * Usage:
     *   val scanner = outputGuard.streamingScanner(512)
     *   for (chunk in sseFlow) {
     *       val result = scanner.addChunk(chunk)
     *       if (!result.passed) { cancel stream; return violation }
     *       emit(chunk)
     *   }
     */
    fun streamingScanner(windowSize: Int = 512): StreamingScanner = StreamingScanner(windowSize)

    inner class StreamingScanner(private val windowSize: Int) {
        private val buffer = StringBuilder()

        /**
         * Append a new chunk to the window buffer and scan.
         * Returns a [GuardResult] — if not passed, the stream should be terminated.
         */
        fun addChunk(chunkContent: String): GuardResult {
            buffer.append(chunkContent)

            // Trim buffer if it exceeds window size, keeping overlap for cross-chunk matches
            if (buffer.length > windowSize) {
                val trimPoint = buffer.length - (windowSize / 2)
                buffer.delete(0, trimPoint)
            }

            val bufferSnapshot = buffer.toString()
            /*log.debug(
                "OutputGuard buffer [len={}]: [{}]",
                bufferSnapshot.length,
                bufferSnapshot.replace("\n", "\\n").replace("\r", "\\r")
                    .replace("\u00A0", "<NBSP>"),
            )*/

            return scan(bufferSnapshot)
        }

        /** Flush: final scan on any remaining buffer content. */
        fun flush(): GuardResult = scan(buffer.toString())
    }
}
