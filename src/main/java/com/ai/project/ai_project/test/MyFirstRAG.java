package com.ai.project.ai_project.test;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.DimensionAwareEmbeddingModel;
import dev.langchain4j.model.embedding.DisabledEmbeddingModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.data.embedding.Embedding;

import java.util.List;

public class MyFirstRAG {
    public static void main(String[] args) {
        // 1. 定义 Embedding 模型（负责把文字转向量）
        // 这里使用的是 LangChain4j 自带的轻量级本地模型，不需要联网
        EmbeddingModel embeddingModel = new DimensionAwareEmbeddingModel() {
            @Override
            public Response<List<Embedding>> embedAll(List<TextSegment> list) {
                return null;
            }
        };

        // 2. 定义向量存储（内存版，相当于 Java 的 HashMap<Vector, Text>）
        EmbeddingStore<TextSegment> embeddingStore = new InMemoryEmbeddingStore<>();

        // 3. 模拟“注入知识”：把你的 CSV 信息录入进去
        String rawData = "张三是一个精通 Spring Boot 的 Java 大牛，住在上海。";
        TextSegment segment = TextSegment.from(rawData);
        Embedding embedding = embeddingModel.embed(segment).content();
        embeddingStore.add(embedding, segment);
        // 4. 用户提问
        String question = "谁是上海的 Java 高手？";
        Embedding questionEmbedding = embeddingModel.embed(question).content();

        // 5. 检索：去向量数据库里找最相似的前 1 条
        EmbeddingSearchRequest embeddingSearchRequest = new EmbeddingSearchRequest();
        var matches = embeddingStore.search(questionEmbedding);

        System.out.println("检索到的最相关知识: " + matches.get(0).embedded().text());
    }
}