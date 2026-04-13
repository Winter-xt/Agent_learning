package com.ai.project.ai_project.test;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class CsvLocalVectorStoreDemo {

    private static final Path CSV_PATH = Path.of("/Users/winter/Downloads/test_users.csv");
    private static final String OLLAMA_BASE_URL = "http://localhost:11434";
    private static final String OLLAMA_MODEL_NAME = "deepseek-r1:8b";

    interface TalentAnalyst {
        @SystemMessage("""
                你是一个专业的招聘专家。
                我会为你提供一些候选人的 CSV 数据。
                请根据数据准确回答问题，如果数据中没有提到，请直说不知道。
                """)
        String analyze(@UserMessage String userQuery);
    }

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

        EmbeddingStoreContentRetriever retriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(3)
                .build();

        TalentAnalyst analyst = AiServices.builder(TalentAnalyst.class)
                .chatLanguageModel(MyFirstRagConfig.ollamaChatModel())
                .contentRetriever(retriever)
                .build();

        String question = args.length > 0 ? args[0] : "帮我看看，这些人里谁最懂 Java？他在哪儿工作？";
        String result = analyst.analyze(question);
        System.out.println("AI 的分析报告：\n" + result);
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

    private static String readEnvOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }
}
