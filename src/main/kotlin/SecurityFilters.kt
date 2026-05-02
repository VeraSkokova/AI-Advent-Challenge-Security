/**
 * Фильтры безопасности для защиты от indirect prompt injection.
 * Реализуют санитизацию входных данных на уровне приложения
 * перед передачей в LLM.
 */
object SecurityFilters {

    /**
     * Удаляет HTML-теги и комментарии из входного текста.
     * Защита от скрытых инструкций в HTML-комментариях.
     */
    fun sanitizeHtml(input: String): String {
        val withoutComments = input.replace(Regex("<!--[\\s\\S]*?-->"), "")
        val withoutTags = withoutComments.replace(Regex("<[^>]+>"), "")
        return withoutTags.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }

    /**
     * Удаляет zero-width Unicode символы, которые могут содержать скрытые payload-ы.
     * Целевые символы:
     *   \u200B — Zero Width Space
     *   \u200C — Zero Width Non-Joiner
     *   \u200D — Zero Width Joiner
     *   \uFEFF — Byte Order Mark / Zero Width No-Break Space
     *   \u2063 — Invisible Separator
     */
    fun sanitizeZeroWidth(input: String): String =
        input.replace(Regex("[\u200B\u200C\u200D\uFEFF\u2063]"), "")

    /**
     * Оборачивает пользовательские данные в XML-подобные маркеры границ.
     * Изолирует содержимое файла от системных инструкций,
     * чтобы LLM не интерпретировал данные как команды.
     */
    fun applyBoundaryMarkers(input: String): String =
        "<user_data>\n$input\n</user_data>"
}
