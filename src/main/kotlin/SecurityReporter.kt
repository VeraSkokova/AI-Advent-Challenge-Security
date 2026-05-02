import java.io.File

/**
 * Генератор отчётов по тестированию безопасности.
 * Записывает результаты тестов в Markdown-файл.
 */
class SecurityReporter(
    private val outputFile: File = File("security_report.md"),
) {

    init {
        outputFile.writeText("# Отчёт: Indirect Prompt Injection — Атака и Защита\n\n")
        outputFile.appendText("_Дата запуска: ${java.time.LocalDateTime.now()}_\n\n")
    }

    fun appendPhase(title: String) {
        outputFile.appendText("---\n\n## $title\n\n")
    }

    fun appendTestResult(testName: String, input: String, llmOutput: String) {
        outputFile.appendText("### $testName\n\n")
        outputFile.appendText("**Входные данные (payload):**\n```\n$input\n```\n\n")
        outputFile.appendText("**Ответ LLM:**\n```\n$llmOutput\n```\n\n")
    }
}
