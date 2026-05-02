import dev.langchain4j.model.ollama.OllamaChatModel
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage

/**
 * Сервис-агент для обработки данных через LLM.
 * Уязвим к indirect prompt injection: передаёт содержимое файлов
 * напрямую в контекст модели без санитизации.
 */
class AgentService(
    baseUrl: String = "http://localhost:11434",
    modelName: String = "llama3.1:8b",
) {

    private val model: OllamaChatModel = OllamaChatModel.builder()
        .baseUrl(baseUrl)
        .modelName(modelName)
        .temperature(0.3)
        .build()

    /**
     * Обрабатывает данные файла с заданным системным промптом.
     * Это уязвимый путь: fileContent вставляется в запрос без фильтрации.
     */
    fun processData(systemPrompt: String, fileContent: String): String {
        val response = model.chat(
            SystemMessage.from(systemPrompt),
            UserMessage.from(fileContent),
        )
        return response.aiMessage().text()
    }
}
