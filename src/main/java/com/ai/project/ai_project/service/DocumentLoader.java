package com.ai.project.ai_project.service;

import com.ai.project.ai_project.domain.ResumeParentBlockEntity;
import com.ai.project.ai_project.mapper.ResumeParentBlockMapper;
import com.ai.project.ai_project.util.IntentRoutingUtils;
import com.ai.project.ai_project.util.ResumeTextUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import dev.langchain4j.store.embedding.filter.logical.Or;
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

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

@Service
public class DocumentLoader {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("pdf", "doc", "docx");
    private static final String SOURCE_TYPE_RESUME = "resume";
    private static final int REDIS_PURGE_BATCH_SIZE = 200;
    private static final String PARENT_BLOCK_CACHE_KEY_PREFIX = "resume:parent:block:";
    private static final int PARENT_BLOCK_CACHE_TTL_SECONDS = 24 * 60 * 60;
    private static final int RESUME_KEYWORD_TEXT_LIMIT = 12000;
    private static final DocumentSplitter CHILD_SPLITTER = DocumentSplitters.recursive(200, 40);
    private static final List<String> FAMOUS_SCHOOL_SIGNALS = List.of(
            "985", "211", "双一流", "c9", "清华", "北大", "北京大学", "复旦", "上海交通大学", "上海交大",
            "浙江大学", "浙大", "中国科学技术大学", "中科大", "南京大学", "南大", "哈尔滨工业大学", "哈工大",
            "西安交通大学", "西安交大", "中国人民大学", "人大", "同济大学", "北京航空航天大学", "北航",
            "北京理工大学", "北理工", "南开大学", "天津大学", "武汉大学", "华中科技大学", "中山大学",
            "厦门大学", "东南大学"
    );
    private static final List<String> BIG_COMPANY_SIGNALS = List.of(
            "阿里", "淘宝", "天猫", "腾讯", "微信", "字节", "抖音", "快手", "美团", "京东", "百度", "网易",
            "拼多多", "小米", "华为", "滴滴", "蚂蚁", "shopee", "google", "meta", "facebook", "amazon",
            "microsoft", "apple", "netflix"
    );

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
    private final ResumeMetadataFilterAiService metadataFilterAiService;
    private final ObjectMapper objectMapper;

    public DocumentLoader(ChatModel chatLanguageModel,
                          EmbeddingModel embeddingModel,
                          EmbeddingStore<TextSegment> embeddingStore,
                          UnifiedJedis unifiedJedis,
                          ResumeParentBlockMapper resumeParentBlockMapper,
                          @org.springframework.beans.factory.annotation.Value("${app.recall.vector-weight:1.0}") double vectorRecallWeight,
                          @org.springframework.beans.factory.annotation.Value("${app.recall.keyword-weight:1.0}") double keywordRecallWeight,
                          @org.springframework.beans.factory.annotation.Value("${app.vector.index-name:talent-index-v3}") String vectorIndexName) {
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
        this.metadataFilterAiService = AiServices.builder(ResumeMetadataFilterAiService.class)
                .chatModel(chatLanguageModel)
                .build();
        this.objectMapper = new ObjectMapper();
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
        ResumeKeywordMetadata resumeKeywordCandidates = extractResumeKeywordMetadata(normalizedText);

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
            putResumeKeywordMetadata(parentMetadata, resolveBlockKeywordMetadata(parentBlock.content(), resumeKeywordCandidates));

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

        //意图识别，判断用户的问题是否需要 RAG
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
     * 1) 先由 LLM 从问题中提取 metadata 约束；
     * 2) 动态构建 LangChain4j Filter 并执行向量/关键词混合召回；
     * 3) 将召回上下文交给模型生成最终答案。
     */
    private String queryResumeWithRag(String userId, String query) {
        String normalizedUserId = ResumeTextUtils.normalizeUserId(userId);

        //提取约束条件
        ResumeFilterConstraints constraints = extractResumeFilterConstraints(query);
        String userIdKey = ResumeTextUtils.toHexKey(normalizedUserId);
        Embedding queryEmbedding = embeddingModel.embed(query).content();

        EmbeddingSearchRequest request = buildResumeSearchRequest(queryEmbedding, buildDynamicResumeFilter(normalizedUserId, constraints), 12, 0.7);
        //向量召回
        List<EmbeddingMatch<TextSegment>> vectorMatches = searchVectorMatchesWithFallback(request, userIdKey, constraints);
        //关键字召回
        List<KeywordHit> keywordHits = searchKeywordHits(normalizedUserId, query, 12);

        List<String> parentContexts = collectHybridParentContexts(vectorMatches, keywordHits, 4);
        if (parentContexts.isEmpty()) {
            EmbeddingSearchRequest relaxedRequest = buildResumeSearchRequest(queryEmbedding, buildBaseResumeFilter(normalizedUserId), 12, 0.7);
            vectorMatches = searchVectorMatchesWithFallback(relaxedRequest, userIdKey, ResumeFilterConstraints.empty());
            parentContexts = collectHybridParentContexts(vectorMatches, keywordHits, 4);
            if (parentContexts.isEmpty()) {
                return "未检索到该用户的简历片段。请确认 userId 与上传时一致，并重新上传一次简历后再查询。";
            }
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

    private EmbeddingSearchRequest buildResumeSearchRequest(Embedding queryEmbedding, Filter filter, int maxResults, double minScore) {
        return EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(maxResults)
                .minScore(minScore)
                .filter(filter)
                .build();
    }

    /**
     * 动态构建简历检索 Filter：
     * - 强制约束：userIdKey/sourceType，保证只检索当前用户简历数据。
     * - 可选约束：由 LLM 提取的 parentType/fileName/contentType/简历关键词。
     */
    private Filter buildDynamicResumeFilter(String normalizedUserId, ResumeFilterConstraints constraints) {
        List<Filter> filters = new ArrayList<>();
        filters.add(buildBaseResumeFilter(normalizedUserId));

        if (!constraints.parentType().isBlank()) {
            filters.add(metadataKey("parentType").isEqualTo(constraints.parentType()));
        }
        if (!constraints.fileName().isBlank()) {
            filters.add(metadataKey("fileName").isEqualTo(constraints.fileName()));
        }
        if (!constraints.contentType().isBlank()) {
            filters.add(metadataKey("contentType").isEqualTo(constraints.contentType()));
        }
        Filter keywordFilter = buildResumeKeywordFilter(constraints);
        if (keywordFilter != null) {
            filters.add(keywordFilter);
        }

        return mergeWithAnd(filters);
    }

    private Filter buildBaseResumeFilter(String normalizedUserId) {
        return new And(
                metadataKey("userIdKey").isEqualTo(ResumeTextUtils.toHexKey(normalizedUserId)),
                metadataKey("sourceType").isEqualTo(SOURCE_TYPE_RESUME)
        );
    }

    /**
     * 将问题中提到的关键词映射到上传简历时写入的 metadata 字段。
     * 同一关键词可命中分类字段或聚合字段；多个关键词之间使用 AND，避免把条件放得太宽。
     */
    private Filter buildResumeKeywordFilter(ResumeFilterConstraints constraints) {
        List<Filter> requiredKeywordFilters = new ArrayList<>();
        addKeywordFilters(requiredKeywordFilters, constraints.skills(), "skillKeywords");
        addKeywordFilters(requiredKeywordFilters, constraints.companies(), "companyKeywords");
        addKeywordFilters(requiredKeywordFilters, constraints.schools(), "schoolKeywords");
        addKeywordFilters(requiredKeywordFilters, constraints.titles(), "titleKeywords");
        addKeywordFilters(requiredKeywordFilters, constraints.projects(), "projectKeywords");
        addKeywordFilters(requiredKeywordFilters, constraints.industries(), "industryKeywords");
        addKeywordFilters(requiredKeywordFilters, constraints.keywords(), "resumeKeywords");
        return requiredKeywordFilters.isEmpty() ? null : mergeWithAnd(requiredKeywordFilters);
    }

    private void addKeywordFilters(List<Filter> target, List<String> keywords, String metadataField) {
        if (keywords == null || keywords.isEmpty()) {
            return;
        }
        for (String rawKeyword : keywords) {
            String keyword = normalizeKeyword(rawKeyword);
            if (keyword.isBlank()) {
                continue;
            }
            target.add(buildAnyKeywordFilter(metadataField, expandSemanticQueryKeywords(keyword)));
        }
    }

    private Filter buildAnyKeywordFilter(String metadataField, List<String> keywords) {
        List<Filter> filters = new ArrayList<>();
        for (String rawKeyword : keywords) {
            String keyword = normalizeKeyword(rawKeyword);
            if (keyword.isBlank()) {
                continue;
            }
            filters.add(metadataKey(metadataField).isIn(keyword));
            if (!"resumeKeywords".equals(metadataField)) {
                filters.add(metadataKey("resumeKeywords").isIn(keyword));
            }
        }
        return mergeWithOr(filters);
    }

    private List<String> expandSemanticQueryKeywords(String keyword) {
        String normalizedKeyword = normalizeKeywordMatchText(keyword);
        List<String> expanded = new ArrayList<>();
        expanded.add(keyword);
        if ("名校".equals(normalizedKeyword)) {
            expanded.addAll(FAMOUS_SCHOOL_SIGNALS);
        } else if ("大厂".equals(normalizedKeyword)) {
            expanded.addAll(BIG_COMPANY_SIGNALS);
        }
        return List.copyOf(new LinkedHashSet<>(expanded));
    }

    /**
     * 由于当前 LangChain4j 版本的 And 仅支持二元构造，
     * 这里将多个过滤条件折叠为嵌套 And。
     */
    private Filter mergeWithAnd(List<Filter> filters) {
        Filter merged = filters.get(0);
        for (int i = 1; i < filters.size(); i++) {
            merged = new And(merged, filters.get(i));
        }
        return merged;
    }

    private Filter mergeWithOr(List<Filter> filters) {
        Filter merged = filters.get(0);
        for (int i = 1; i < filters.size(); i++) {
            merged = new Or(merged, filters.get(i));
        }
        return merged;
    }

    /**
     * 调用 LLM 提取结构化约束，并做白名单清洗，避免模型输出污染检索条件。
     */
    private ResumeFilterConstraints extractResumeFilterConstraints(String query) {
        try {
            String json = metadataFilterAiService.extract(query);
            JsonNode node = objectMapper.readTree(json);
            String parentType = normalizeParentType(node.path("parentType").asText(""));
            String fileName = ResumeTextUtils.safe(node.path("fileName").asText(""));
            String contentType = ResumeTextUtils.safe(node.path("contentType").asText(""));
            return new ResumeFilterConstraints(
                    parentType,
                    fileName,
                    contentType,
                    readKeywordArray(node, "skills"),
                    readKeywordArray(node, "companies"),
                    readKeywordArray(node, "schools"),
                    readKeywordArray(node, "titles"),
                    readKeywordArray(node, "projects"),
                    readKeywordArray(node, "industries"),
                    readKeywordArray(node, "keywords")
            );
        } catch (Exception ignored) {
            return ResumeFilterConstraints.empty();
        }
    }

    private ResumeKeywordMetadata extractResumeKeywordMetadata(String normalizedText) {
        try {
            String textForLlm = normalizedText.length() <= RESUME_KEYWORD_TEXT_LIMIT
                    ? normalizedText
                    : normalizedText.substring(0, RESUME_KEYWORD_TEXT_LIMIT);
            String json = metadataFilterAiService.extractResumeKeywords(textForLlm);
            JsonNode node = objectMapper.readTree(json);
            return new ResumeKeywordMetadata(
                    readKeywordArray(node, "skills"),
                    readKeywordArray(node, "companies"),
                    readKeywordArray(node, "schools"),
                    readKeywordArray(node, "titles"),
                    readKeywordArray(node, "projects"),
                    readKeywordArray(node, "industries"),
                    readKeywordArray(node, "keywords")
            );
        } catch (Exception ignored) {
            return ResumeKeywordMetadata.empty();
        }
    }

    private ResumeKeywordMetadata resolveBlockKeywordMetadata(String blockContent, ResumeKeywordMetadata resumeKeywordCandidates) {
        return new ResumeKeywordMetadata(
                keepKeywordsAppearingInBlock(blockContent, resumeKeywordCandidates.skills()),
                keepKeywordsAppearingInBlock(blockContent, resumeKeywordCandidates.companies()),
                keepKeywordsAppearingInBlock(blockContent, resumeKeywordCandidates.schools()),
                keepKeywordsAppearingInBlock(blockContent, resumeKeywordCandidates.titles()),
                keepKeywordsAppearingInBlock(blockContent, resumeKeywordCandidates.projects()),
                keepKeywordsAppearingInBlock(blockContent, resumeKeywordCandidates.industries()),
                keepKeywordsAppearingInBlock(blockContent, resumeKeywordCandidates.keywords())
        );
    }

    private List<String> keepKeywordsAppearingInBlock(String blockContent, List<String> candidates) {
        if (blockContent == null || blockContent.isBlank() || candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        String normalizedBlock = normalizeKeywordMatchText(blockContent);
        Set<String> matched = new LinkedHashSet<>();
        for (String candidate : candidates) {
            String keyword = normalizeKeyword(candidate);
            if (!keyword.isBlank()
                    && (normalizedBlock.contains(normalizeKeywordMatchText(keyword))
                    || semanticKeywordAppearsInBlock(keyword, normalizedBlock))) {
                matched.add(keyword);
            }
        }
        return List.copyOf(matched);
    }

    private boolean semanticKeywordAppearsInBlock(String keyword, String normalizedBlock) {
        String normalizedKeyword = normalizeKeywordMatchText(keyword);
        if ("名校".equals(normalizedKeyword)) {
            return containsAnyNormalized(normalizedBlock, FAMOUS_SCHOOL_SIGNALS);
        }
        if ("大厂".equals(normalizedKeyword)) {
            return containsAnyNormalized(normalizedBlock, BIG_COMPANY_SIGNALS);
        }
        return false;
    }

    private boolean containsAnyNormalized(String normalizedText, List<String> signals) {
        if (normalizedText == null || normalizedText.isBlank() || signals == null || signals.isEmpty()) {
            return false;
        }
        for (String signal : signals) {
            if (normalizedText.contains(normalizeKeywordMatchText(signal))) {
                return true;
            }
        }
        return false;
    }

    private void putResumeKeywordMetadata(Metadata metadata, ResumeKeywordMetadata keywordMetadata) {
        metadata.put("skillKeywords", joinKeywords(keywordMetadata.skills()));
        metadata.put("companyKeywords", joinKeywords(keywordMetadata.companies()));
        metadata.put("schoolKeywords", joinKeywords(keywordMetadata.schools()));
        metadata.put("titleKeywords", joinKeywords(keywordMetadata.titles()));
        metadata.put("projectKeywords", joinKeywords(keywordMetadata.projects()));
        metadata.put("industryKeywords", joinKeywords(keywordMetadata.industries()));

        List<String> allKeywords = new ArrayList<>();
        allKeywords.addAll(keywordMetadata.skills());
        allKeywords.addAll(keywordMetadata.companies());
        allKeywords.addAll(keywordMetadata.schools());
        allKeywords.addAll(keywordMetadata.titles());
        allKeywords.addAll(keywordMetadata.projects());
        allKeywords.addAll(keywordMetadata.industries());
        allKeywords.addAll(keywordMetadata.keywords());
        metadata.put("resumeKeywords", joinKeywords(allKeywords));
    }

    private List<String> readKeywordArray(JsonNode node, String fieldName) {
        JsonNode arrayNode = node.path(fieldName);
        if (!arrayNode.isArray()) {
            return List.of();
        }
        Set<String> keywords = new LinkedHashSet<>();
        for (JsonNode item : arrayNode) {
            String keyword = normalizeKeyword(item.asText(""));
            if (!keyword.isBlank()) {
                keywords.add(keyword);
            }
            if (keywords.size() >= 20) {
                break;
            }
        }
        return List.copyOf(keywords);
    }

    private String joinKeywords(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return "";
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String keyword : keywords) {
            String value = normalizeKeyword(keyword);
            if (!value.isBlank()) {
                normalized.add(value);
            }
        }
        return String.join(" ", normalized);
    }

    private String normalizeKeywordMatchText(String raw) {
        return ResumeTextUtils.safe(raw)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "");
    }

    private String normalizeKeyword(String raw) {
        String value = ResumeTextUtils.safe(raw)
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
        if (value.length() > 30) {
            value = value.substring(0, 30).trim();
        }
        return value;
    }

    /**
     * parentType 白名单：仅允许 project/resume，其他值全部清空。
     */
    private String normalizeParentType(String raw) {
        String value = ResumeTextUtils.safe(raw).toLowerCase(Locale.ROOT);
        if ("project".equals(value) || "resume".equals(value)) {
            return value;
        }
        return "";
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
                                                                               String userIdKey,
                                                                               ResumeFilterConstraints constraints) {
        try {
            List<EmbeddingMatch<TextSegment>> primary = embeddingStore.search(request).matches();
            if (!primary.isEmpty()) {
                return primary;
            }
        } catch (Exception ignored) {
            // Fall back to an unfiltered vector search and apply supported filters in memory.
        }

        // Fallback for schema/version drift: retrieve without filter and apply metadata filtering in JVM.
        EmbeddingSearchRequest fallbackRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(request.queryEmbedding())
                .maxResults(50)
                .minScore(0.0)
                .build();
        return embeddingStore.search(fallbackRequest).matches().stream()
                .filter(match -> match.embedded() != null && match.embedded().metadata() != null)
                .filter(match -> metadataMatchesResumeFilter(match.embedded().metadata(), userIdKey, constraints))
                .limit(12)
                .toList();
    }

    private boolean metadataMatchesResumeFilter(Metadata metadata, String userIdKey, ResumeFilterConstraints constraints) {
        if (metadata == null) {
            return false;
        }
        if (!SOURCE_TYPE_RESUME.equals(ResumeTextUtils.safe(metadata.getString("sourceType")))) {
            return false;
        }
        if (!userIdKey.equals(ResumeTextUtils.safe(metadata.getString("userIdKey")))) {
            return false;
        }
        if (!constraints.parentType().isBlank()
                && !constraints.parentType().equals(ResumeTextUtils.safe(metadata.getString("parentType")))) {
            return false;
        }
        if (!constraints.fileName().isBlank()
                && !constraints.fileName().equals(ResumeTextUtils.safe(metadata.getString("fileName")))) {
            return false;
        }
        if (!constraints.contentType().isBlank()
                && !constraints.contentType().equals(ResumeTextUtils.safe(metadata.getString("contentType")))) {
            return false;
        }
        return metadataMatchesKeywords(metadata, constraints);
    }

    private boolean metadataMatchesKeywords(Metadata metadata, ResumeFilterConstraints constraints) {
        return metadataMatchesKeywords(metadata, constraints.skills(), "skillKeywords")
                && metadataMatchesKeywords(metadata, constraints.companies(), "companyKeywords")
                && metadataMatchesKeywords(metadata, constraints.schools(), "schoolKeywords")
                && metadataMatchesKeywords(metadata, constraints.titles(), "titleKeywords")
                && metadataMatchesKeywords(metadata, constraints.projects(), "projectKeywords")
                && metadataMatchesKeywords(metadata, constraints.industries(), "industryKeywords")
                && metadataMatchesKeywords(metadata, constraints.keywords(), "resumeKeywords");
    }

    private boolean metadataMatchesKeywords(Metadata metadata, List<String> keywords, String metadataField) {
        if (keywords == null || keywords.isEmpty()) {
            return true;
        }
        String fieldValue = normalizeKeywordMatchText(metadata.getString(metadataField));
        String resumeValue = "resumeKeywords".equals(metadataField)
                ? fieldValue
                : normalizeKeywordMatchText(metadata.getString("resumeKeywords"));
        for (String rawKeyword : keywords) {
            if (!metadataMatchesAnyKeyword(fieldValue, resumeValue, expandSemanticQueryKeywords(rawKeyword))) {
                return false;
            }
        }
        return true;
    }

    private boolean metadataMatchesAnyKeyword(String fieldValue, String resumeValue, List<String> keywords) {
        for (String rawKeyword : keywords) {
            String keyword = normalizeKeywordMatchText(rawKeyword);
            if (!keyword.isBlank() && (fieldValue.contains(keyword) || resumeValue.contains(keyword))) {
                return true;
            }
        }
        return false;
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

    /**
     * LLM 提取后的可用 metadata 约束。
     */
    private record ResumeFilterConstraints(
            String parentType,
            String fileName,
            String contentType,
            List<String> skills,
            List<String> companies,
            List<String> schools,
            List<String> titles,
            List<String> projects,
            List<String> industries,
            List<String> keywords
    ) {
        private static ResumeFilterConstraints empty() {
            return new ResumeFilterConstraints(
                    "",
                    "",
                    "",
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of()
            );
        }
    }

    /**
     * 上传简历时由 LLM 读取简历后抽取出的可检索 metadata 关键词。
     */
    private record ResumeKeywordMetadata(
            List<String> skills,
            List<String> companies,
            List<String> schools,
            List<String> titles,
            List<String> projects,
            List<String> industries,
            List<String> keywords
    ) {
        private static ResumeKeywordMetadata empty() {
            return new ResumeKeywordMetadata(
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of()
            );
        }
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
