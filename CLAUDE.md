# AI-Advent Challenge: LLM Security & Architecture

## 🏗 Build & Run Commands
- Build project: `./gradlew build`
- Run interactive security tests: Run `PromptInjectionTest.kt` directly via IntelliJ IDEA.

## 🛠 Tech Stack & Environment
- **Language**: Pure Kotlin (Strictly NO Spring Boot).
- **AI Integration**: LangChain4j + Local Ollama.
- **Local Models**: `llama3.1:8b` (primary for complex routing and security tests), `qwen2:1.5b` (for simple extraction tasks).
- **Environment**: macOS (Apple Silicon M4, 16GB RAM) — optimize context windows and avoid running multiple heavy models simultaneously.

## 🎯 Current Focus: Week 9-11 (LLM Security & Inference)
- **Prompt Injection (Day 11):** Researching LLM vulnerabilities. Testing direct/indirect injections, jailbreaks (DAN), instruction overrides, and system prompt extraction.
- **Prompt Hardening:** Implementing defenses purely at the prompt level (using XML tags like `<user_input>`, sandwich defense, and explicit role enforcement). Software-level defenses (LLM Gateway, Output Guards) are out of scope for these specific tests.
- **Inference Decomposition (Day 9):** Breaking down complex multi-language ticket parsing (`train_tickets.jsonl`) into micro-queries (analyze -> decide -> format).

## 📋 Claude Code Guidelines
1. **No Frameworks:** Strictly pure Kotlin. Do not suggest or add Spring Framework dependencies, annotations, or complex DI containers.
2. **Interactive Console First:** Use `readlnOrNull()` and `while(true)` loops for interactive testing interfaces.
3. **Prompt Engineering Style:**
    - When writing system prompts, use clear delimiters (e.g., Kotlin multiline strings `"""` and XML tags) to separate system instructions from user inputs.
    - For protected prompts, always apply the "Sandwich Defense" (rules -> input -> rules).
4. **Language Protocol:** Write all Kotlin code logic, variable names, and code comments in English. System prompts for the LLM and interactive console outputs should be in Russian to match the challenge dataset.
5. **Simplicity:** Keep the architecture flat and straightforward. Use `AiServices` from LangChain4j for structured data extraction where appropriate, but prefer raw model calls when testing security vulnerabilities.