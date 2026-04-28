package com.ai.project.ai_project;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.logical.And;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

@SpringBootTest
@Disabled("手动调试父子检索时启用")
class DocumentLoaderParentChildDebugTest {

    @Autowired(required = false)
    private EmbeddingStore<TextSegment> embeddingStore;

    @Autowired(required = false)
    private EmbeddingModel embeddingModel;

    @Test
    void debugParentChildRetrieve() {
        if (embeddingStore == null || embeddingModel == null) {
            System.out.println("EmbeddingStore 或 EmbeddingModel 未注入，请先确认应用配置可启动");
            return;
        }

        String userId = "default";
        String query = "候选人的技术栈有哪些";
        int topK = 5;

        Filter filter = new And(
                metadataKey("userIdKey").isEqualTo(toHexKey(userId)),
                metadataKey("sourceType").isEqualTo("resume")
        );

        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(embeddingModel.embed(query).content())
                .maxResults(topK)
                .minScore(0.0)
                .filter(filter)
                .build();

        var result = embeddingStore.search(request);
        System.out.println("命中条数: " + result.matches().size());

        for (int i = 0; i < result.matches().size(); i++) {
            var match = result.matches().get(i);
            var segment = match.embedded();
            var metadata = segment == null ? null : segment.metadata();
            String childText = segment == null ? "" : abbreviate(segment.text(), 180);
            String parentType = metadata == null ? "" : metadata.getString("parentType");
            String parentIndex = metadata == null ? "" : metadata.getString("parentIndex");
            String childIndex = metadata == null ? "" : metadata.getString("childIndex");
            String parentBlock = metadata == null ? "" : abbreviate(metadata.getString("parentBlock"), 260);

            System.out.println("\n== Hit " + (i + 1) + " ==");
            System.out.println("score=" + match.score());
            System.out.println("parentType=" + parentType + ", parentIndex=" + parentIndex + ", childIndex=" + childIndex);
            System.out.println("childText=" + childText);
            System.out.println("parentBlock=" + parentBlock);
        }
    }

    private String toHexKey(String raw) {
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String abbreviate(String text, int maxLen) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        return normalized.substring(0, maxLen) + "...";
    }
}
