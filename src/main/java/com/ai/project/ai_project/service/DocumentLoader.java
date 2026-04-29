package com.ai.project.ai_project.service;

import com.ai.project.ai_project.domain.ResumeParentBlockEntity;
import com.ai.project.ai_project.mapper.ResumeParentBlockMapper;
import com.ai.project.ai_project.util.IntentRoutingUtils;
import com.ai.project.ai_project.util.ResumeTextUtils;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.logical.And;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.search.FTSearchParams;
import redis.clients.jedis.search.SearchResult;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

@Service
public class DocumentLoader {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("pdf", "doc", "docx");
    private static final String SOURCE_TYPE_RESUME = "resume";
    private static final int REDIS_PURGE_BATCH_SIZE = 200;
    private static final String PARENT_BLOCK_CACHE_KEY_PREFIX = "resume:parent:block:";
    private static final int PARENT_BLOCK_CACHE_TTL_SECONDS = 24 * 60 * 60;
    private static final DocumentSplitter CHILD_SPLITTER = DocumentSplitters.recursive(200, 40);

    private final ChatModel chatLanguageModel;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final UnifiedJedis unifiedJedis;
    private final ResumeParentBlockMapper resumeParentBlockMapper;
    private final ApacheTikaDocumentParser parser;
    private final String vectorIndexName;
    private final double vectorRecallWeight;
    private final double keywordRecallWeight;
    private final AiService intentClassifier;

    public DocumentLoader(ChatModel chatLanguageModel,
                          EmbeddingModel embeddingModel,
                          EmbeddingStore<TextSegment> embeddingStore,
                          UnifiedJedis unifiedJedis,
                          ResumeParentBlockMapper resumeParentBlockMapper,
                          @org.springframework.beans.factory.annotation.Value("${app.recall.vector-weight:1.0}") double vectorRecallWeight,
                          @org.springframework.beans.factory.annotation.Value("${app.recall.keyword-weight:1.0}") double keywordRecallWeight,
                          @org.springframework.beans.factory.annotation.Value("${app.vector.index-name:talent-index-v2}") String vectorIndexName) {
        this.chatLanguageModel = chatLanguageModel;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.unifiedJedis = unifiedJedis;
        this.resumeParentBlockMapper = resumeParentBlockMapper;
        this.parser = new ApacheTikaDocumentParser();
        this.vectorRecallWeight = vectorRecallWeight;
        this.keywordRecallWeight = keywordRecallWeight;
        this.vectorIndexName = vectorIndexName;
        this.intentClassifier = AiServices.builder(AiService.class)
                .chatModel(chatLanguageModel)
                .build();
    }

    @Transactional(rollbackFor = Exception.class)
    public UploadResult loadResume(String userId, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }

        String normalizedUserId = ResumeTextUtils.normalizeUserId(userId);

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
        metadata.put("userIdKey", ResumeTextUtils.toHexKey(normalizedUserId));
        metadata.put("sourceType", SOURCE_TYPE_RESUME);
        metadata.put("fileName", fileName == null ? "" : fileName);
        metadata.put("contentType", file.getContentType() == null ? "" : file.getContentType());
        metadata.put("uploadedAt", Instant.now().toString());

        String normalizedText = ResumeTextUtils.normalizeText(text);
        String userIdKey = ResumeTextUtils.toHexKey(normalizedUserId);
        purgeExistingResumeData(userIdKey);

        //拆分文档内容作为父文档并存储
        List<ParentBlock> parentBlocks = extractParentBlocks(normalizedText);
        if (parentBlocks.isEmpty()) {
            parentBlocks = List.of(new ParentBlock("resume", normalizedText));
        }

        List<TextSegment> childSegments = new ArrayList<>();
        for (int i = 0; i < parentBlocks.size(); i++) {
            ParentBlock parentBlock = parentBlocks.get(i);
            Long parentBlockId = saveParentBlock(normalizedUserId, userIdKey, i, parentBlock);
            Metadata parentMetadata = metadata.copy();
            parentMetadata.put("parentType", parentBlock.type());
            parentMetadata.put("parentIndex", String.valueOf(i));
            parentMetadata.put("parentBlockId", String.valueOf(parentBlockId));

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

    /**
     * 将解析出的简历文本按“父块”切分，便于后续父子分片和去重召回。
     */
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

            if (ResumeTextUtils.isSectionHeader(trimmed)) {
                appendBlock(blocks, currentType, current);
                current = new StringBuilder();
                currentType = ResumeTextUtils.isProjectHeader(trimmed) ? "project" : "resume";
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

    private void appendBlock(List<ParentBlock> blocks, String type, StringBuilder content) {
        String value = content.toString().trim();
        if (!value.isBlank()) {
            String resolvedType = ResumeTextUtils.resolveParentType(type, value);
            blocks.add(new ParentBlock(resolvedType, value));
        }
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

    /**
     * 简历问答入口：
     * 先做意图判断，再决定是否执行简历 RAG 检索。
     */
    public String queryResume(String userId, String query) {
        validateResumeQueryParams(userId, query);

        Intent intent = classifyIntent(query);
        if (shouldUseResumeRag(intent)) {
            return queryResumeWithRag(userId, query);
        }

        String prompt = """
                你是一个招聘助手。
                当前用户在“简历问答”通道提问，但该问题更偏向闲聊或意图不明确。
                请先简短回应用户，并引导用户提出与简历检索相关的问题，例如：
                - 候选人有哪些项目经验？
                - 候选人是否有 Java/Spring 经验？

                用户问题：%s
                """.formatted(query);
        return chatLanguageModel.chat(prompt);
    }

    /**
     * 简历 RAG 检索问答：
     * 向量召回 + Redis关键词召回混合排序，最后交给大模型基于上下文回答。
     */
    private String queryResumeWithRag(String userId, String query) {
        String normalizedUserId = ResumeTextUtils.normalizeUserId(userId);

        Filter filter = new And(
                metadataKey("userIdKey").isEqualTo(ResumeTextUtils.toHexKey(normalizedUserId)),
                metadataKey("sourceType").isEqualTo(SOURCE_TYPE_RESUME)
        );

        String userIdKey = ResumeTextUtils.toHexKey(normalizedUserId);
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(embeddingModel.embed(query).content())
                .maxResults(12)
                .minScore(0.7)
                .filter(filter)
                .build();

        List<EmbeddingMatch<TextSegment>> vectorMatches = searchVectorMatchesWithFallback(request, userIdKey);
        List<KeywordHit> keywordHits = searchKeywordHits(normalizedUserId, query, 12);
        if (vectorMatches.isEmpty() && keywordHits.isEmpty()) {
            return "未检索到该用户的简历片段。请确认 userId 与上传时一致，并重新上传一次简历后再查询。";
        }

        List<String> parentContexts = collectHybridParentContexts(vectorMatches, keywordHits, 4);
        if (parentContexts.isEmpty()) {
            return "未检索到该用户的简历片段。请确认 userId 与上传时一致，并重新上传一次简历后再查询。";
        }

        String mergedContext = String.join("\n\n---\n\n", parentContexts);
        String prompt = """
                你是一个简历分析助手。
                你会收到按父块去重后的简历上下文，每个父块可能由多个子块检索命中后合并而来。
                请优先理解 parentBlock 再回答，不要编造。
                如果简历中没有相关信息，请明确回答：未在该用户简历中找到相关信息。

                【简历上下文】
                %s

                【问题】
                %s
                """.formatted(mergedContext, query);
        return chatLanguageModel.chat(prompt);
    }

    private void validateResumeQueryParams(String userId, String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
    }

    private Intent classifyIntent(String query) {
        String label = intentClassifier.classify(query);
        return IntentRoutingUtils.parseIntentLabel(label);
    }

    private boolean shouldUseResumeRag(Intent intent) {
        return intent == Intent.RESUME_QUERY;
    }

    private List<EmbeddingMatch<TextSegment>> searchVectorMatchesWithFallback(EmbeddingSearchRequest request,
                                                                               String userIdKey) {
        List<EmbeddingMatch<TextSegment>> primary = embeddingStore.search(request).matches();
        if (!primary.isEmpty()) {
            return primary;
        }

        // Fallback for schema/version drift: retrieve without filter and apply metadata filtering in JVM.
        EmbeddingSearchRequest fallbackRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(request.queryEmbedding())
                .maxResults(50)
                .minScore(0.0)
                .build();
        return embeddingStore.search(fallbackRequest).matches().stream()
                .filter(match -> match.embedded() != null && match.embedded().metadata() != null)
                .filter(match -> SOURCE_TYPE_RESUME.equals(ResumeTextUtils.safe(match.embedded().metadata().getString("sourceType"))))
                .filter(match -> userIdKey.equals(ResumeTextUtils.safe(match.embedded().metadata().getString("userIdKey"))))
                .limit(12)
                .toList();
    }

    /**
     * 将向量召回与关键词召回融合后，按父块去重并输出可直接拼接到 Prompt 的上下文片段。
     */
    private List<String> collectHybridParentContexts(List<EmbeddingMatch<TextSegment>> vectorMatches,
                                                     List<KeywordHit> keywordHits,
                                                     int maxParents) {
        Map<String, ParentCandidate> candidates = new LinkedHashMap<>();

        for (int i = 0; i < vectorMatches.size(); i++) {
            EmbeddingMatch<TextSegment> match = vectorMatches.get(i);
            TextSegment segment = match.embedded();
            if (segment == null) {
                continue;
            }
            Metadata metadata = segment.metadata();
            String parentIndex = metadata == null ? "" : ResumeTextUtils.safe(metadata.getString("parentIndex"));
            String parentType = metadata == null ? "" : ResumeTextUtils.safe(metadata.getString("parentType"));
            Long parentBlockId = parseParentBlockId(metadata == null ? "" : ResumeTextUtils.safe(metadata.getString("parentBlockId")));
            String fallback = ResumeTextUtils.safe(segment.text());

            String parentKey = parentIndex.isBlank() ? "fallback-" + fallback.hashCode() : parentIndex;
            String context = fallback;
            double score = weightedReciprocalRank(i, vectorRecallWeight);

            ParentCandidate incoming = new ParentCandidate(parentKey, parentType, parentBlockId, context, score);
            ParentCandidate existing = candidates.get(parentKey);
            if (existing == null || incoming.score() > existing.score()) {
                candidates.put(parentKey, incoming);
            }
        }

        for (int i = 0; i < keywordHits.size(); i++) {
            KeywordHit hit = keywordHits.get(i);
            String parentKey = hit.parentIndex().isBlank() ? "kw-" + i : hit.parentIndex();
            ParentCandidate existing = candidates.get(parentKey);
            double score = weightedReciprocalRank(i, keywordRecallWeight);
            Long keywordParentBlockId = parseParentBlockId(hit.parentBlockId());
            if (existing == null) {
                candidates.put(parentKey, new ParentCandidate(parentKey, hit.parentType(), keywordParentBlockId, "", score));
            } else {
                candidates.put(parentKey, new ParentCandidate(
                        existing.parentKey(),
                        existing.parentType().isBlank() ? hit.parentType() : existing.parentType(),
                        existing.parentBlockId() == null ? keywordParentBlockId : existing.parentBlockId(),
                        existing.context(),
                        existing.score() + score
                ));
            }
        }

        Map<Long, String> parentContentById = loadParentBlockContents(
                candidates.values().stream()
                        .map(ParentCandidate::parentBlockId)
                        .filter(id -> id != null && id > 0)
                        .toList()
        );

        return candidates.values().stream()
                .sorted(Comparator.comparingDouble(ParentCandidate::score).reversed())
                .limit(maxParents)
                .map(candidate -> {
                    String type = candidate.parentType().isBlank() ? "resume" : candidate.parentType();
                    String dbContent = candidate.parentBlockId() == null ? "" : ResumeTextUtils.safe(parentContentById.get(candidate.parentBlockId()));
                    String content = dbContent.isBlank() ? candidate.context() : dbContent;
                    return "【parentType=" + type + ", parentIndex=" + candidate.parentKey() + "】\n" + content;
                })
                .toList();
    }

    private List<KeywordHit> searchKeywordHits(String normalizedUserId, String query, int limit) {
        try {
            String userIdKey = ResumeTextUtils.toHexKey(normalizedUserId);
            String textClause = ResumeTextUtils.buildRedisTextClause(query);
            if (textClause.isBlank()) {
                return List.of();
            }
            String redisQuery = "@userIdKey:{" + userIdKey + "} @sourceType:{resume} @text:(" + textClause + ")";
            FTSearchParams params = FTSearchParams.searchParams()
                    .limit(0, limit)
                    .returnFields("parentType", "parentIndex", "parentBlockId")
                    .dialect(2);
            SearchResult result = unifiedJedis.ftSearch(vectorIndexName, redisQuery, params);
            if (result == null || result.getDocuments() == null) {
                return List.of();
            }
            return result.getDocuments().stream()
                    .map(this::toKeywordHit)
                    .filter(hit -> !hit.parentBlockId().isBlank())
                    .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private KeywordHit toKeywordHit(redis.clients.jedis.search.Document doc) {
        if (doc == null) {
            return new KeywordHit("", "", "");
        }
        return new KeywordHit(
                ResumeTextUtils.safe(doc.getString("parentType")),
                ResumeTextUtils.safe(doc.getString("parentIndex")),
                ResumeTextUtils.safe(doc.getString("parentBlockId"))
        );
    }

    private Long saveParentBlock(String normalizedUserId, String userIdKey, int parentIndex, ParentBlock parentBlock) {
        ResumeParentBlockEntity entity = new ResumeParentBlockEntity();
        entity.setUserId(normalizedUserId);
        entity.setUserIdKey(userIdKey);
        entity.setSourceType(SOURCE_TYPE_RESUME);
        entity.setParentIndex(String.valueOf(parentIndex));
        entity.setParentType(parentBlock.type());
        entity.setContent(parentBlock.content());
        resumeParentBlockMapper.insert(entity);
        if (entity.getId() == null) {
            throw new IllegalStateException("保存父块失败，未生成主键");
        }
        return entity.getId();
    }

    private void purgeExistingResumeData(String userIdKey) {
        resumeParentBlockMapper.deleteByUserIdKeyAndSourceType(userIdKey, SOURCE_TYPE_RESUME);
        purgeRedisVectorsByUser(userIdKey);
    }

    private void purgeRedisVectorsByUser(String userIdKey) {
        while (true) {
            String redisQuery = "@userIdKey:{" + userIdKey + "} @sourceType:{resume}";
            FTSearchParams params = FTSearchParams.searchParams()
                    .limit(0, REDIS_PURGE_BATCH_SIZE)
                    .dialect(2);
            SearchResult result = unifiedJedis.ftSearch(vectorIndexName, redisQuery, params);
            if (result == null || result.getDocuments() == null || result.getDocuments().isEmpty()) {
                return;
            }
            List<String> redisDocIds = result.getDocuments().stream()
                    .map(redis.clients.jedis.search.Document::getId)
                    .filter(id -> id != null && !id.isBlank())
                    .toList();
            if (redisDocIds.isEmpty()) {
                return;
            }
            unifiedJedis.del(redisDocIds.toArray(new String[0]));
            if (redisDocIds.size() < REDIS_PURGE_BATCH_SIZE) {
                return;
            }
        }
    }

    private Map<Long, String> loadParentBlockContents(List<Long> parentBlockIds) {
        if (parentBlockIds == null || parentBlockIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> contentById = new LinkedHashMap<>();
        List<Long> missedIds = new ArrayList<>();
        for (Long parentBlockId : parentBlockIds) {
            String cached = ResumeTextUtils.safe(readParentBlockFromCache(parentBlockId));
            if (!cached.isBlank()) {
                contentById.put(parentBlockId, cached);
            } else {
                missedIds.add(parentBlockId);
            }
        }
        if (missedIds.isEmpty()) {
            return contentById;
        }

        List<ResumeParentBlockEntity> entities = resumeParentBlockMapper.selectBatchIds(missedIds);
        if (entities == null || entities.isEmpty()) {
            return contentById;
        }
        for (ResumeParentBlockEntity entity : entities) {
            if (entity == null || entity.getId() == null) {
                continue;
            }
            String content = ResumeTextUtils.safe(entity.getContent());
            contentById.put(entity.getId(), content);
            writeParentBlockToCache(entity.getId(), content);
        }
        return contentById;
    }

    private Long parseParentBlockId(String rawId) {
        String value = ResumeTextUtils.safe(rawId);
        if (value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private double weightedReciprocalRank(int rankIndex, double weight) {
        return weight / (rankIndex + 60.0d);
    }

    private String readParentBlockFromCache(Long parentBlockId) {
        if (parentBlockId == null || parentBlockId <= 0) {
            return "";
        }
        try {
            return unifiedJedis.hget(parentBlockCacheKey(parentBlockId), "content");
        } catch (Exception ignored) {
            return "";
        }
    }

    private void writeParentBlockToCache(Long parentBlockId, String content) {
        if (parentBlockId == null || parentBlockId <= 0 || content == null || content.isBlank()) {
            return;
        }
        String key = parentBlockCacheKey(parentBlockId);
        try {
            unifiedJedis.hset(key, "content", content);
            unifiedJedis.expire(key, PARENT_BLOCK_CACHE_TTL_SECONDS);
        } catch (Exception ignored) {
            // Ignore cache write failure and keep DB as source of truth.
        }
    }

    private String parentBlockCacheKey(Long parentBlockId) {
        return PARENT_BLOCK_CACHE_KEY_PREFIX + parentBlockId;
    }

    private record ParentBlock(String type, String content) {
    }

    private record ParentCandidate(String parentKey, String parentType, Long parentBlockId, String context, double score) {
    }

    private record KeywordHit(String parentType, String parentIndex, String parentBlockId) {
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
