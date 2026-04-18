package com.ai.project.ai_project.config;

import com.ai.project.ai_project.event.ChatMemoryPersistEvent;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;
import dev.langchain4j.community.store.embedding.redis.RedisEmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

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

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Bean
    public ChatLanguageModel chatLanguageModel() {
        return ModelConfig.deepseekChatModel(deepseekApiKey);
    }

    @Bean
    public StreamingChatLanguageModel streamingChatLanguageModel() {
        return ModelConfig.deepseekStreamingChatModel(deepseekApiKey);
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        return new AllMiniLmL6V2QuantizedEmbeddingModel();
    }

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        return RedisEmbeddingStore.builder()
                .host(redisHost)
                .port(redisPort)
                .dimension(384)
                .indexName("talent-index")
                .prefix("talent:")
                .build();
    }

    @Bean
    public ChatMemoryStore chatMemoryStore(StringRedisTemplate redisTemplate,
                                           ApplicationEventPublisher eventPublisher) {
        return new ChatMemoryStore() {
            private static final String KEY_PREFIX = "chat:memory:";

            @Override
            public List<ChatMessage> getMessages(Object memoryId) {
                String key = KEY_PREFIX + memoryId;
                String json = redisTemplate.opsForValue().get(key);
                if (json != null) {
                    return new ArrayList<>(ChatMessageDeserializer.messagesFromJson(json));
                }
                return new ArrayList<>();
            }

            @Override
            public void updateMessages(Object memoryId, List<ChatMessage> messages) {
                String id = String.valueOf(memoryId);
                String json = ChatMessageSerializer.messagesToJson(messages);
                redisTemplate.opsForValue().set(KEY_PREFIX + id, json);
                eventPublisher.publishEvent(new ChatMemoryPersistEvent(id, json, false));
            }

            @Override
            public void deleteMessages(Object memoryId) {
                String id = String.valueOf(memoryId);
                redisTemplate.delete(KEY_PREFIX + id);
                eventPublisher.publishEvent(new ChatMemoryPersistEvent(id, null, true));
            }
        };
    }
}
