package com.ai.project.ai_project.test;

import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import java.util.concurrent.CountDownLatch;

public class MyFirstRAG {
    public static void main(String[] args) throws InterruptedException {
        OpenAiStreamingChatModel model = MyFirstRagConfig.openAiStreamingChatModel();

        CountDownLatch latch = new CountDownLatch(1);
        model.chat("测试API连通性", new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                System.out.print(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                System.out.println();
                latch.countDown();
            }

            @Override
            public void onError(Throwable error) {
                error.printStackTrace();
                latch.countDown();
            }
        });

        latch.await();
    }
}
