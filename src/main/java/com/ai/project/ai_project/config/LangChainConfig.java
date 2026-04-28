package com.ai.project.ai_project.config;

import com.ai.project.ai_project.event.ChatMemoryPersistEvent;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
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
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.search.schemafields.TagField;
import redis.clients.jedis.search.schemafields.TextField;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Configuration
public class LangChainConfig {
    private static final String MEMORY_CATEGORY_SEPARATOR = "::";
    private static final String DEFAULT_MEMORY_CATEGORY = "default";

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

    @Value("${app.vector.index-name:talent-index-v2}")
    private String vectorIndexName;

    @Value("${app.vector.prefix:talent:v2:}")
    private String vectorPrefix;

    @Bean
    public ChatModel chatLanguageModel() {
        return ModelConfig.deepseekChatModel(deepseekApiKey);
    }

    @Bean
    public StreamingChatModel streamingChatLanguageModel() {
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
                .indexName(vectorIndexName)
                .prefix(vectorPrefix)
                .metadataConfig(Map.of(
                        "userIdKey", TagField.of("$.userIdKey").as("userIdKey"),
                        "sourceType", TagField.of("$.sourceType").as("sourceType"),
                        "userId", TagField.of("$.userId").as("userId"),
                        "fileName", TextField.of("$.fileName").as("fileName"),
                        "contentType", TagField.of("$.contentType").as("contentType"),
                        "uploadedAt", TagField.of("$.uploadedAt").as("uploadedAt"),
                        "parentType", TagField.of("$.parentType").as("parentType"),
                        "parentIndex", TagField.of("$.parentIndex").as("parentIndex"),
                        "parentBlock", TextField.of("$.parentBlock").as("parentBlock"),
                        "childIndex", TagField.of("$.childIndex").as("childIndex")
                ))
                .build();
    }

    @Bean
    public UnifiedJedis unifiedJedis() {
        return new UnifiedJedis(new HostAndPort(redisHost, redisPort));
    }

    @Bean
    public ChatMemoryStore chatMemoryStore(StringRedisTemplate redisTemplate,
                                           ApplicationEventPublisher eventPublisher) {
        return new ChatMemoryStore() {
            private static final String KEY_PREFIX = "chat:memory:";

            @Override
            public List<ChatMessage> getMessages(Object memoryId) {
                MemoryScope scope = parseMemoryScope(memoryId);
                String key = KEY_PREFIX + scope.category + ":" + scope.id;
                String json = redisTemplate.opsForValue().get(key);
                if (json != null) {
                    return new ArrayList<>(ChatMessageDeserializer.messagesFromJson(json));
                }
                return new ArrayList<>();
            }

            @Override
            public void updateMessages(Object memoryId, List<ChatMessage> messages) {
                MemoryScope scope = parseMemoryScope(memoryId);
                String json = ChatMessageSerializer.messagesToJson(messages);
                redisTemplate.opsForValue().set(KEY_PREFIX + scope.category + ":" + scope.id, json);
                eventPublisher.publishEvent(new ChatMemoryPersistEvent(scope.id, scope.category, json, false));
            }

            @Override
            public void deleteMessages(Object memoryId) {
                MemoryScope scope = parseMemoryScope(memoryId);
                redisTemplate.delete(KEY_PREFIX + scope.category + ":" + scope.id);
                eventPublisher.publishEvent(new ChatMemoryPersistEvent(scope.id, scope.category, null, true));
            }
        };
    }

    private MemoryScope parseMemoryScope(Object memoryId) {
        String raw = String.valueOf(memoryId);
        if (raw.contains(MEMORY_CATEGORY_SEPARATOR)) {
            String[] parts = raw.split(MEMORY_CATEGORY_SEPARATOR, 2);
            String category = parts[0] == null || parts[0].isBlank() ? DEFAULT_MEMORY_CATEGORY : parts[0];
            String id = parts.length < 2 || parts[1] == null || parts[1].isBlank() ? "default-user" : parts[1];
            return new MemoryScope(id, category);
        }
        return new MemoryScope(raw, DEFAULT_MEMORY_CATEGORY);
    }

    private static class MemoryScope {
        private final String id;
        private final String category;

        private MemoryScope(String id, String category) {
            this.id = id;
            this.category = category;
        }
    }
}
