package com.ai.project.ai_project.test;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class CsvLocalVectorStoreDemo {

    private static final Path CSV_PATH = Path.of("/Users/winter/Downloads/test_users.csv");

    public static void main(String[] args) throws IOException {
        EmbeddingModel embeddingModel = new AllMiniLmL6V2QuantizedEmbeddingModel();
        InMemoryEmbeddingStore<TextSegment> embeddingStore = new InMemoryEmbeddingStore<>();

        List<TextSegment> segments = loadCsvAsSegments(CSV_PATH);
        if (segments.isEmpty()) {
            System.out.println("CSV 没有可用数据: " + CSV_PATH);
            return;
        }

        for (TextSegment segment : segments) {
            Embedding embedding = embeddingModel.embed(segment.text()).content();
            embeddingStore.add(embedding, segment);
        }
        System.out.println("已写入向量条数: " + segments.size());

        String query = "查找张三";
        Embedding queryEmbedding = embeddingModel.embed(query).content();

        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(3)
                .build();

        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(searchRequest);

        System.out.println("检索关键词: " + query);
        System.out.println("Top " + result.matches().size() + " 结果:");
        for (EmbeddingMatch<TextSegment> match : result.matches()) {
            System.out.printf("- score=%.4f, text=%s%n", match.score(), match.embedded().text());
        }
    }

    private static List<TextSegment> loadCsvAsSegments(Path csvPath) throws IOException {
        List<String> lines = Files.readAllLines(csvPath);
        List<TextSegment> segments = new ArrayList<>();

        if (lines.isEmpty()) {
            return segments;
        }

        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (!line.isEmpty()) {
                segments.add(TextSegment.from(line));
            }
        }
        return segments;
    }
}
