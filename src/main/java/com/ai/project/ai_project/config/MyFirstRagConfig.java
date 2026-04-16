package com.ai.project.ai_project.config;

import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;

public final class MyFirstRagConfig {

    private static final String BASE_URL = "https://api-vip.codex-for.me/v1";
    private static final String MODEL_NAME = "gpt-5.2";
    private static final String OLLAMA_BASE_URL = "http://localhost:11434";
    private static final String OLLAMA_QWEN_MODEL_NAME = "qwen3.5:9b";
    private static final String DEEPSEEK_API_MODEL_NAME = "deepseek-chat";

    private MyFirstRagConfig() {
    }

    public static OpenAiStreamingChatModel openAiStreamingChatModel(String apiKey) {
        return OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .modelName(MODEL_NAME)
                .build();
    }

    public static OpenAiStreamingChatModel deepseekStreamingChatModel(String apiKey) {
        return OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl("https://api.deepseek.com")
                .modelName(DEEPSEEK_API_MODEL_NAME)
                .build();
    }

    public static GoogleAiGeminiChatModel googleAiGeminiChatModel(String apiKey) {
        return GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName("gemini-2.5-flash")
                .build();
    }

    public static OpenAiChatModel deepseekChatModel(String apiKey) {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl("https://api.deepseek.com")
                .modelName(DEEPSEEK_API_MODEL_NAME)
                .build();
    }

    public static OllamaChatModel ollamaQwenChatModel() {
        String baseUrl = readEnvOrDefault("OLLAMA_BASE_URL", OLLAMA_BASE_URL);
        String modelName = readEnvOrDefault("OLLAMA_MODEL_NAME", OLLAMA_QWEN_MODEL_NAME);

        return OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelName)
                .build();
    }

    private static String readEnvOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }
}
