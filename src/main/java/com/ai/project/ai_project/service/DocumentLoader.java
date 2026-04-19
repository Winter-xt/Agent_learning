package com.ai.project.ai_project.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.IngestionResult;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.logical.And;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

@Service
public class DocumentLoader {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("pdf", "doc", "docx");

    private final ChatLanguageModel chatLanguageModel;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final ApacheTikaDocumentParser parser;
    private final DocumentSplitter splitter;

    public DocumentLoader(ChatLanguageModel chatLanguageModel,
                          EmbeddingModel embeddingModel,
                          EmbeddingStore<TextSegment> embeddingStore) {
        this.chatLanguageModel = chatLanguageModel;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.parser = new ApacheTikaDocumentParser();
        this.splitter = DocumentSplitters.recursive(500, 100);
    }

    public UploadResult loadResume(String userId, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }

        String normalizedUserId = normalizeUserId(userId);

        String fileName = file.getOriginalFilename();
        validateExtension(fileName);

        Document parsedDocument;
        try (InputStream inputStream = file.getInputStream()) {
            parsedDocument = parser.parse(inputStream);
        }

        String text = parsedDocument.text();
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("文档内容为空，无法入库");
        }

        Metadata metadata = parsedDocument.metadata() == null
                ? new Metadata()
                : parsedDocument.metadata().copy();
        metadata.put("userId", normalizedUserId);
        metadata.put("userIdKey", toHexKey(normalizedUserId));
        metadata.put("sourceType", "resume");
        metadata.put("fileName", fileName == null ? "" : fileName);
        metadata.put("contentType", file.getContentType() == null ? "" : file.getContentType());
        metadata.put("uploadedAt", Instant.now().toString());

        Document enrichedDocument = Document.from(text, metadata);
        int segmentCount = splitter.split(enrichedDocument).size();

        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .documentSplitter(splitter)
                .build();

        IngestionResult ingestionResult = ingestor.ingest(enrichedDocument);

        return new UploadResult(
                normalizedUserId,
                fileName == null ? "" : fileName,
                segmentCount,
                text.length(),
                ingestionResult.tokenUsage() == null ? null : ingestionResult.tokenUsage().totalTokenCount()
        );
    }

    private void validateExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            throw new IllegalArgumentException("文件名无效，仅支持 PDF/DOC/DOCX");
        }
        String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("仅支持上传 PDF、DOC、DOCX 格式");
        }
    }

    public String queryResume(String userId, String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }

        String normalizedUserId = normalizeUserId(userId);

        Filter filter = new And(
                metadataKey("userIdKey").isEqualTo(toHexKey(normalizedUserId)),
                metadataKey("sourceType").isEqualTo("resume")
        );

        EmbeddingStoreContentRetriever retriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(5)
                .minScore(0.0)
                .filter(filter)
                .build();

        if (retriever.retrieve(Query.from(query)).isEmpty()) {
            return "未检索到该用户的简历片段。请确认 userId 与上传时一致，并重新上传一次简历后再查询。";
        }

        ResumeQaAssistant assistant = AiServices.builder(ResumeQaAssistant.class)
                .chatLanguageModel(chatLanguageModel)
                .contentRetriever(retriever)
                .build();

        return assistant.answer(query);
    }

    interface ResumeQaAssistant {
        @SystemMessage("""
                你是一个简历分析助手。
                请仅基于检索到的简历内容回答问题，不要编造。
                如果简历中没有相关信息，请明确回答：未在该用户简历中找到相关信息。
                """)
        String answer(@UserMessage String question);
    }

    private String toHexKey(String raw) {
        String value = raw == null ? "" : raw;
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String normalizeUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return "default-user";
        }
        return userId.trim();
    }

    public record UploadResult(
            String userId,
            String fileName,
            int segmentCount,
            int characterCount,
            Integer embeddingTokenCount
    ) {
    }
}
