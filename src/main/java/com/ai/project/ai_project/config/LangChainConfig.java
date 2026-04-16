package com.ai.project.ai_project.config;

import com.ai.project.ai_project.domain.ChatMemoryEntity;
import com.ai.project.ai_project.mapper.ChatMemoryMapper;
// Removed old MyFirstRagConfig import if any
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class LangChainConfig {

    @Value("${DEEPSEEK_API_KEY}")
    private String deepseekApiKey;

    @Value("${GEMINI_AI_KEY}")
    private String geminiApiKey;

    @Value("${OPENAI_API_KEY}")
    private String openAiApiKey;

    @Bean
    public ChatLanguageModel chatLanguageModel() {
        return MyFirstRagConfig.deepseekChatModel(deepseekApiKey);
    }

    @Bean
    public StreamingChatLanguageModel streamingChatLanguageModel() {
        return MyFirstRagConfig.deepseekStreamingChatModel(deepseekApiKey);
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        return new AllMiniLmL6V2QuantizedEmbeddingModel();
    }

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        return new InMemoryEmbeddingStore<>();
    }

    @Bean
    public ChatMemoryStore chatMemoryStore(ChatMemoryMapper mapper) {
        return new ChatMemoryStore() {
            @Override
            public List<ChatMessage> getMessages(Object memoryId) {
                ChatMemoryEntity entity = mapper.selectById(String.valueOf(memoryId));
                if (entity != null && entity.getMessages() != null) {
                    return new ArrayList<>(ChatMessageDeserializer.messagesFromJson(entity.getMessages()));
                }
                return new ArrayList<>();
            }

            @Override
            public void updateMessages(Object memoryId, List<ChatMessage> messages) {
                String json = ChatMessageSerializer.messagesToJson(messages);
                ChatMemoryEntity entity = new ChatMemoryEntity(String.valueOf(memoryId), json);
                if (mapper.selectById(entity.getId()) == null) {
                    mapper.insert(entity);
                } else {
                    mapper.updateById(entity);
                }
            }

            @Override
            public void deleteMessages(Object memoryId) {
                mapper.deleteById(String.valueOf(memoryId));
            }
        };
    }
}
