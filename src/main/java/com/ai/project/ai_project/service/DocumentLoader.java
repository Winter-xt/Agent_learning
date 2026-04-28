package com.ai.project.ai_project.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.logical.And;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

@Service
public class DocumentLoader {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("pdf", "doc", "docx");
    private static final String SOURCE_TYPE_RESUME = "resume";
    private static final DocumentSplitter CHILD_SPLITTER = DocumentSplitters.recursive(200, 40);
    private static final Pattern PROJECT_SECTION_HEADER = Pattern.compile(
            "^(项目经历|项目经验|项目背景)(\\s*[A-Za-z0-9一二三四五六七八九十]+)?\\s*[:：]?$"
    );
    private static final Pattern GENERIC_SECTION_HEADER = Pattern.compile(
            "^(基本信息|个人信息|联系方式|教育经历|工作经历|实习经历|项目经历|项目经验|专业技能|技能栈|技术栈|证书|获奖情况|自我评价|个人评价|个人总结)(\\s*[A-Za-z0-9一二三四五六七八九十]+)?\\s*[:：]?$"
    );
    private static final Pattern DATE_RANGE_PATTERN = Pattern.compile(
            "(19|20)\\d{2}[./-]\\d{1,2}\\s*[-~到至]\\s*((19|20)\\d{2}[./-]\\d{1,2}|至今|现在)"
    );

    private final ChatLanguageModel chatLanguageModel;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final ApacheTikaDocumentParser parser;

    public DocumentLoader(ChatLanguageModel chatLanguageModel,
                          EmbeddingModel embeddingModel,
                          EmbeddingStore<TextSegment> embeddingStore) {
        this.chatLanguageModel = chatLanguageModel;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.parser = new ApacheTikaDocumentParser();
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
        metadata.put("sourceType", SOURCE_TYPE_RESUME);
        metadata.put("fileName", fileName == null ? "" : fileName);
        metadata.put("contentType", file.getContentType() == null ? "" : file.getContentType());
        metadata.put("uploadedAt", Instant.now().toString());

        String normalizedText = normalizeText(text);
        //拆分文档内容
        List<ParentBlock> parentBlocks = extractParentBlocks(normalizedText);
        if (parentBlocks.isEmpty()) {
            parentBlocks = List.of(new ParentBlock("resume", normalizedText));
        }

        List<TextSegment> childSegments = new ArrayList<>();
        for (int i = 0; i < parentBlocks.size(); i++) {
            ParentBlock parentBlock = parentBlocks.get(i);
            Metadata parentMetadata = metadata.copy();
            parentMetadata.put("parentType", parentBlock.type());
            parentMetadata.put("parentIndex", String.valueOf(i));
            parentMetadata.put("parentBlock", parentBlock.content());

            Document parentDocument = Document.from(parentBlock.content(), parentMetadata);
            List<TextSegment> children = CHILD_SPLITTER.split(parentDocument);
            for (int childIndex = 0; childIndex < children.size(); childIndex++) {
                TextSegment child = children.get(childIndex);
                if (child.text() == null || child.text().isBlank()) {
                    continue;
                }
                Metadata childMetadata = child.metadata() == null ? new Metadata() : child.metadata().copy();
                childMetadata.put("childIndex", String.valueOf(childIndex));
                childSegments.add(TextSegment.from(child.text().trim(), childMetadata));
            }
        }

        if (childSegments.isEmpty()) {
            throw new IllegalArgumentException("文档分片为空，无法入库");
        }

        Response<List<Embedding>> response = embeddingModel.embedAll(childSegments);
        embeddingStore.addAll(response.content(), childSegments);

        return new UploadResult(
                normalizedUserId,
                fileName == null ? "" : fileName,
                childSegments.size(),
                text.length(),
                response.tokenUsage() == null ? null : response.tokenUsage().totalTokenCount()
        );
    }

    private String normalizeText(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n').replaceAll("\n{3,}", "\n\n").trim();
    }

    private List<ParentBlock> extractParentBlocks(String text) {
        List<ParentBlock> blocks = new ArrayList<>();
        String[] lines = text.split("\n");

        String currentType = "resume";
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isBlank()) {
                if (!current.isEmpty()) {
                    current.append('\n');
                }
                continue;
            }

            if (isSectionHeader(trimmed)) {
                appendBlock(blocks, currentType, current);
                current = new StringBuilder();
                currentType = PROJECT_SECTION_HEADER.matcher(trimmed).matches() ? "project" : "resume";
                current.append(trimmed);
                continue;
            }

            if (!current.isEmpty()) {
                current.append('\n');
            }
            current.append(trimmed);
        }

        appendBlock(blocks, currentType, current);
        return blocks;
    }

    private boolean isSectionHeader(String line) {
        if (line.length() > 50) {
            return false;
        }
        return GENERIC_SECTION_HEADER.matcher(line).matches();
    }

    private void appendBlock(List<ParentBlock> blocks, String type, StringBuilder content) {
        String value = content.toString().trim();
        if (!value.isBlank()) {
            String resolvedType = resolveParentType(type, value);
            blocks.add(new ParentBlock(resolvedType, value));
        }
    }

    private String resolveParentType(String headerType, String content) {
        if ("project".equals(headerType)) {
            return "project";
        }
        if (looksLikeProjectContent(content)) {
            return "project";
        }
        return "resume";
    }

    private boolean looksLikeProjectContent(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }

        boolean hasDateRange = DATE_RANGE_PATTERN.matcher(content).find();
        boolean hasProjectNouns = containsAny(content, "项目", "系统", "平台", "业务", "架构");
        boolean hasWorkSignals = containsAny(content, "负责", "主导", "实现", "优化", "设计", "技术方案", "性能");

        return (hasDateRange && hasProjectNouns) || (hasProjectNouns && hasWorkSignals);
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
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
                metadataKey("sourceType").isEqualTo(SOURCE_TYPE_RESUME)
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

    private record ParentBlock(String type, String content) {
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
