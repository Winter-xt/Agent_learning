package com.ai.project.ai_project.test;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;

public final class MyFirstRagConfig {

    private static final String BASE_URL = "https://api-vip.codex-for.me/v1";
    private static final String MODEL_NAME = "gpt-5.2";
    private static final String OLLAMA_BASE_URL = "http://localhost:11434";
    private static final String OLLAMA_MODEL_NAME = "deepseek-r1:8b";

    private MyFirstRagConfig() {
    }

    public static OpenAiStreamingChatModel openAiStreamingChatModel() {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(BASE_URL)
                .apiKey(requireOpenAiApiKey())
                .modelName(MODEL_NAME)
                .build();
    }

    public static OpenAiChatModel openAiChatModel() {
        return OpenAiChatModel.builder()
                .baseUrl(BASE_URL)
                .apiKey(requireOpenAiApiKey())
                .modelName(MODEL_NAME)
                .build();
    }

    public static OllamaChatModel ollamaChatModel() {
        String baseUrl = readEnvOrDefault("OLLAMA_BASE_URL", OLLAMA_BASE_URL);
        String modelName = readEnvOrDefault("OLLAMA_MODEL_NAME", OLLAMA_MODEL_NAME);

        return OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelName)
                .build();
    }

    private static String readEnvOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }

    private static String requireOpenAiApiKey() {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("请先设置环境变量 OPENAI_API_KEY");
        }
        return apiKey;
    }
}
