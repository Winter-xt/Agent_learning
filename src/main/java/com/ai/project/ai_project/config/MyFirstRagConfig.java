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

    public static OpenAiStreamingChatModel openAiStreamingChatModel() {
        return OpenAiStreamingChatModel.builder()
                .apiKey(requireOpenAiApiKey())
                .modelName(MODEL_NAME)
                .build();
    }
    public static GoogleAiGeminiChatModel googleAiGeminiChatModel() {
        return GoogleAiGeminiChatModel.builder()
                .apiKey(System.getenv("GEMINI_AI_KEY"))
                .modelName("gemini-2.5-flash")
                .build();
    }


    public static OpenAiChatModel deepseekChatModel() {
        return OpenAiChatModel.builder()
                .apiKey(requireDeepseekApiKey())
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

    private static String requireOpenAiApiKey() {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("请先设置环境变量 OPENAI_API_KEY");
        }
        return apiKey;
    }

    private static String requireDeepseekApiKey() {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("请先设置环境变量 DEEPSEEK_API_KEY");
        }
        return apiKey;
    }
}
