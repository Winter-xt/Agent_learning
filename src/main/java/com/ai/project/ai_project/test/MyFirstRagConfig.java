package com.ai.project.ai_project.test;

import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

public final class MyFirstRagConfig {

    private static final String BASE_URL = "https://api-vip.codex-for.me/v1";
    private static final String MODEL_NAME = "gpt-5.2";

    private MyFirstRagConfig() {
    }

    public static OpenAiStreamingChatModel openAiStreamingChatModel() {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("请先设置环境变量 OPENAI_API_KEY");
        }

        return OpenAiStreamingChatModel.builder()
                .baseUrl(BASE_URL)
                .apiKey(apiKey)
                .modelName(MODEL_NAME)
                .build();
    }
}
