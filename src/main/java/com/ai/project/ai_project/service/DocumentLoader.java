package com.ai.project.ai_project.service;

import com.ai.project.ai_project.domain.ResumeDocumentEntity;
import com.ai.project.ai_project.domain.ResumeParentBlockEntity;
import com.ai.project.ai_project.mapper.ResumeDocumentMapper;
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
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.logical.And;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StopWatch;
import org.springframework.web.multipart.MultipartFile;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.search.FTSearchParams;
import redis.clients.jedis.search.SearchResult;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

@Service
public class DocumentLoader {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("pdf", "doc", "docx");
    private static final String SOURCE_TYPE_RESUME = "resume";
    private static final int REDIS_PURGE_BATCH_SIZE = 200;
    private static final int ZIP_ENTRY_READ_BUFFER_SIZE = 8192;
    private static final String PARENT_BLOCK_CACHE_KEY_PREFIX = "resume:parent:block:";
    private static final int PARENT_BLOCK_CACHE_TTL_SECONDS = 24 * 60 * 60;
    private static final int RESUME_KEYWORD_TEXT_LIMIT = 12000;
    private static final int RESUME_QUERY_REWRITE_MAX_LENGTH = 500;
    private static final int SCORE_CLIFF_MIN_CONTEXTS = 1;
    private static final int HORIZONTAL_COMPARE_SCORE_CLIFF_MIN_CONTEXTS = 4;
    private static final double SCORE_CLIFF_RETAIN_RATIO = 0.45d;
    private static final double SCORE_CLIFF_MIN_GAP = 0.12d;
    private static final int RESUME_VECTOR_MAX_RESULTS = 50;
    private static final int HORIZONTAL_COMPARE_VECTOR_MAX_RESULTS = 80;
    private static final int RESUME_KEYWORD_MAX_RESULTS = 12;
    private static final int HORIZONTAL_COMPARE_KEYWORD_MAX_RESULTS = 24;
    private static final int RESUME_PARENT_CONTEXTS = 4;
    private static final int HORIZONTAL_COMPARE_PARENT_CONTEXTS = 8;
    private static final DocumentSplitter CHILD_SPLITTER = DocumentSplitters.recursive(200, 40);
    private static final Pattern QUERY_TOKEN_SPLITTER = Pattern.compile("[\\s,，。！？；;:：()（）\\-_/\\.]+");
    private static final Pattern PREPROCESS_INTENT_PATTERN = Pattern.compile("\"?intent\"?\\s*[:：]\\s*\"?([A-Z_]+)\"?", Pattern.CASE_INSENSITIVE);
    private static final Pattern PREPROCESS_REWRITTEN_QUERY_PATTERN = Pattern.compile("\"?rewrittenQuery\"?\\s*[:：]\\s*(\"(?:\\\\.|[^\"])*\"|[^,}\\r\\n]+)", Pattern.CASE_INSENSITIVE);
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
    private final StreamingChatModel streamingChatLanguageModel;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final UnifiedJedis unifiedJedis;
    private final ResumeParentBlockMapper resumeParentBlockMapper;
    private final ResumeDocumentMapper resumeDocumentMapper;
    private final ResumeQueryTraceService resumeQueryTraceService;
    private final Executor aiTaskExecutor;
    private final ApacheTikaDocumentParser parser;
    private final String vectorIndexName;
    private final Path resumeStorageDir;
    private final double vectorRecallWeight;
    private final double keywordRecallWeight;
    private final ResumeMetadataFilterAiService metadataFilterAiService;
    private final ResumeToolAssistant resumeToolAssistant;
    private final ThreadLocal<ResumeToolExecutionContext> resumeToolExecutionContext = new ThreadLocal<>();
    private final ObjectMapper objectMapper;

    public DocumentLoader(ChatModel chatLanguageModel,
                          StreamingChatModel streamingChatLanguageModel,
                          EmbeddingModel embeddingModel,
                          EmbeddingStore<TextSegment> embeddingStore,
                          UnifiedJedis unifiedJedis,
                          ResumeParentBlockMapper resumeParentBlockMapper,
                          ResumeDocumentMapper resumeDocumentMapper,
                          ResumeQueryTraceService resumeQueryTraceService,
                          @Qualifier("aiTaskExecutor") Executor aiTaskExecutor,
                          @org.springframework.beans.factory.annotation.Value("${app.recall.vector-weight:1.0}") double vectorRecallWeight,
                          @org.springframework.beans.factory.annotation.Value("${app.recall.keyword-weight:1.0}") double keywordRecallWeight,
                          @org.springframework.beans.factory.annotation.Value("${app.vector.index-name:talent-index-v4}") String vectorIndexName,
                          @org.springframework.beans.factory.annotation.Value("${app.resume.storage-dir:${user.home}/.ai_project/resumes}") String resumeStorageDir) {
        this.chatLanguageModel = chatLanguageModel;
        this.streamingChatLanguageModel = streamingChatLanguageModel;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.unifiedJedis = unifiedJedis;
        this.resumeParentBlockMapper = resumeParentBlockMapper;
        this.resumeDocumentMapper = resumeDocumentMapper;
        this.resumeQueryTraceService = resumeQueryTraceService;
        this.aiTaskExecutor = aiTaskExecutor;
        this.parser = new ApacheTikaDocumentParser();
        this.vectorRecallWeight = vectorRecallWeight;
        this.keywordRecallWeight = keywordRecallWeight;
        this.vectorIndexName = vectorIndexName;
        this.resumeStorageDir = Path.of(resumeStorageDir);
        this.metadataFilterAiService = AiServices.builder(ResumeMetadataFilterAiService.class)
                .chatModel(chatLanguageModel)
                .build();
        this.resumeToolAssistant = AiServices.builder(ResumeToolAssistant.class)
                .chatModel(chatLanguageModel)
                .tools(new ResumeSearchTools())
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
        ParsedResume parsedResume = parseResumeFile(fileName, file.getContentType(), file.getBytes());
        if (!parsedResume.profile().isResume()) {
            throw new IllegalArgumentException("上传文件不是候选人简历");
        }

        String userIdKey = ResumeTextUtils.toHexKey(normalizedUserId);
        return upsertParsedResume(normalizedUserId, userIdKey, parsedResume);
    }

    @Transactional(rollbackFor = Exception.class)
    public BatchUploadResult loadResumes(String userId, MultipartFile[] files) throws IOException {
        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("上传文件不能为空");
        }

        String normalizedUserId = ResumeTextUtils.normalizeUserId(userId);
        String userIdKey = ResumeTextUtils.toHexKey(normalizedUserId);
        List<UploadedResumeFile> uploadedFiles = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            uploadedFiles.addAll(expandUploadFile(file));
        }

        List<UploadResult> uploaded = new ArrayList<>();
        List<SkippedUpload> skipped = new ArrayList<>();
        Set<String> seenCandidateKeys = new LinkedHashSet<>();
        for (UploadedResumeFile uploadedFile : uploadedFiles) {
            try {
                validateExtension(uploadedFile.fileName());
                ParsedResume parsedResume = parseResumeFile(uploadedFile.fileName(), uploadedFile.contentType(), uploadedFile.bytes());
                if (!parsedResume.profile().isResume()) {
                    skipped.add(new SkippedUpload(uploadedFile.fileName(), "不是候选人简历"));
                    continue;
                }
                String candidateName = resolveCandidateName(parsedResume.profile().candidateName(), parsedResume.fileName());
                String candidateNameKey = normalizeCandidateNameKey(candidateName);
                if (!seenCandidateKeys.add(candidateNameKey)) {
                    skipped.add(new SkippedUpload(uploadedFile.fileName(), "同一批次中候选人重复：" + candidateName));
                    continue;
                }
                ResumeDocumentEntity existing = resumeDocumentMapper
                        .selectByUserIdKeyAndCandidateNameKeyAndOriginalFileName(
                                userIdKey,
                                SOURCE_TYPE_RESUME,
                                candidateNameKey,
                                uploadedFile.fileName()
                        );
                if (existing != null) {
                    deleteResumeDataByEntity(userIdKey, existing);
                    uploaded.add(persistParsedResume(normalizedUserId, userIdKey, parsedResume));
                    continue;
                }
                if (resumeDocumentMapper.selectByUserIdKeyAndCandidateNameKey(userIdKey, SOURCE_TYPE_RESUME, candidateNameKey) != null) {
                    skipped.add(new SkippedUpload(uploadedFile.fileName(), "候选人已存在：" + candidateName));
                    continue;
                }
                uploaded.add(persistParsedResume(normalizedUserId, userIdKey, parsedResume));
            } catch (IllegalArgumentException e) {
                skipped.add(new SkippedUpload(uploadedFile.fileName(), e.getMessage()));
            } catch (Exception e) {
                skipped.add(new SkippedUpload(uploadedFile.fileName(), "解析或入库失败"));
            }
        }

        return new BatchUploadResult(normalizedUserId, uploaded, skipped);
    }

    private UploadResult upsertParsedResume(String normalizedUserId, String userIdKey, ParsedResume parsedResume) throws IOException {
        String candidateName = resolveCandidateName(parsedResume.profile().candidateName(), parsedResume.fileName());
        String candidateNameKey = normalizeCandidateNameKey(candidateName);
        ResumeDocumentEntity existing = resumeDocumentMapper
                .selectByUserIdKeyAndCandidateNameKeyAndOriginalFileName(
                        userIdKey,
                        SOURCE_TYPE_RESUME,
                        candidateNameKey,
                        ResumeTextUtils.safe(parsedResume.fileName())
                );
        if (existing != null) {
            deleteResumeDataByEntity(userIdKey, existing);
        }
        return persistParsedResume(normalizedUserId, userIdKey, parsedResume);
    }

    private ParsedResume parseResumeFile(String fileName, String contentType, byte[] bytes) throws IOException {
        Document parsedDocument;
        try (InputStream inputStream = new ByteArrayInputStream(bytes)) {
            parsedDocument = parser.parse(inputStream);
        }
        String text = parsedDocument.text();
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("文档内容为空，无法入库");
        }

        String normalizedText = ResumeTextUtils.normalizeText(text);
        ResumeProfile profile = extractResumeProfile(normalizedText);
        return new ParsedResume(fileName, contentType, bytes, parsedDocument, normalizedText, profile);
    }

    private UploadResult persistParsedResume(String normalizedUserId, String userIdKey, ParsedResume parsedResume) throws IOException {
        Document parsedDocument = parsedResume.document();
        Metadata metadata = parsedDocument.metadata() == null
                ? new Metadata()
                : parsedDocument.metadata().copy();
        ResumeDocumentEntity resumeDocument = createResumeDocument(normalizedUserId, userIdKey, parsedResume);
        String storedPath = storeOriginalResumeFile(userIdKey, resumeDocument.getId(), parsedResume.fileName(), parsedResume.bytes());
        resumeDocument.setStoredFilePath(storedPath);
        resumeDocumentMapper.updateById(resumeDocument);

        metadata.put("userId", normalizedUserId);
        metadata.put("userIdKey", ResumeTextUtils.toHexKey(normalizedUserId));
        metadata.put("sourceType", SOURCE_TYPE_RESUME);
        metadata.put("resumeId", String.valueOf(resumeDocument.getId()));
        metadata.put("candidateName", resumeDocument.getCandidateName());
        metadata.put("fileName", parsedResume.fileName() == null ? "" : parsedResume.fileName());
        metadata.put("contentType", parsedResume.contentType() == null ? "" : parsedResume.contentType());
        metadata.put("uploadedAt", Instant.now().toString());

        //拆分文档内容作为父文档并存储
        List<ParentBlock> parentBlocks = extractParentBlocks(parsedResume.normalizedText());
        if (parentBlocks.isEmpty()) {
            parentBlocks = List.of(new ParentBlock("resume", parsedResume.normalizedText()));
        }

        ResumeKeywordMetadata resumeKeywordCandidates = extractResumeKeywordMetadata(parsedResume.normalizedText());
        List<TextSegment> childSegments = new ArrayList<>();
        for (int i = 0; i < parentBlocks.size(); i++) {
            ParentBlock parentBlock = parentBlocks.get(i);
            Long parentBlockId = saveParentBlock(normalizedUserId, userIdKey, resumeDocument.getId(), i, parentBlock);
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
        resumeDocument.setSegmentCount(childSegments.size());
        resumeDocumentMapper.updateById(resumeDocument);

        return new UploadResult(
                normalizedUserId,
                resumeDocument.getId(),
                resumeDocument.getCandidateName(),
                parsedResume.fileName() == null ? "" : parsedResume.fileName(),
                childSegments.size(),
                parsedResume.normalizedText().length(),
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

    private List<UploadedResumeFile> expandUploadFile(MultipartFile file) throws IOException {
        String fileName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        if (!isZipFile(fileName, file.getContentType())) {
            return List.of(new UploadedResumeFile(fileName, file.getContentType(), file.getBytes()));
        }

        List<UploadedResumeFile> expanded = new ArrayList<>();
        try (ZipInputStream zipInputStream = new ZipInputStream(file.getInputStream(), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String entryFileName = sanitizeFileName(entry.getName());
                if (entryFileName.isBlank()) {
                    continue;
                }
                expanded.add(new UploadedResumeFile(entryFileName, "application/octet-stream", readZipEntryBytes(zipInputStream)));
            }
        }
        return expanded;
    }

    private byte[] readZipEntryBytes(ZipInputStream zipInputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[ZIP_ENTRY_READ_BUFFER_SIZE];
        int read;
        while ((read = zipInputStream.read(buffer)) >= 0) {
            outputStream.write(buffer, 0, read);
        }
        return outputStream.toByteArray();
    }

    private boolean isZipFile(String fileName, String contentType) {
        String normalizedFileName = ResumeTextUtils.safe(fileName).toLowerCase(Locale.ROOT);
        String normalizedContentType = ResumeTextUtils.safe(contentType).toLowerCase(Locale.ROOT);
        return normalizedFileName.endsWith(".zip") || normalizedContentType.contains("zip");
    }

    private ResumeProfile extractResumeProfile(String normalizedText) {
        try {
            String textForLlm = normalizedText.length() <= RESUME_KEYWORD_TEXT_LIMIT
                    ? normalizedText
                    : normalizedText.substring(0, RESUME_KEYWORD_TEXT_LIMIT);
            String json = metadataFilterAiService.extractResumeProfile(textForLlm);
            JsonNode node = objectMapper.readTree(json);
            boolean isResume = node.path("isResume").asBoolean(false);
            String candidateName = normalizeKeyword(node.path("candidateName").asText(""));
            return new ResumeProfile(isResume, candidateName);
        } catch (Exception ignored) {
            boolean looksLikeResume = containsAnyText(normalizedText, "教育经历", "工作经历", "项目经历", "专业技能", "技能栈", "个人信息");
            return new ResumeProfile(looksLikeResume, "");
        }
    }

    private boolean containsAnyText(String text, String... keywords) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private ResumeDocumentEntity createResumeDocument(String normalizedUserId,
                                                      String userIdKey,
                                                      ParsedResume parsedResume) {
        ResumeDocumentEntity entity = new ResumeDocumentEntity();
        entity.setUserId(normalizedUserId);
        entity.setUserIdKey(userIdKey);
        entity.setSourceType(SOURCE_TYPE_RESUME);
        String candidateName = resolveCandidateName(parsedResume.profile().candidateName(), parsedResume.fileName());
        entity.setCandidateName(candidateName);
        entity.setCandidateNameKey(normalizeCandidateNameKey(candidateName));
        entity.setOriginalFileName(parsedResume.fileName() == null ? "" : parsedResume.fileName());
        entity.setStoredFilePath("");
        entity.setContentType(parsedResume.contentType() == null ? "" : parsedResume.contentType());
        entity.setFileSize((long) parsedResume.bytes().length);
        entity.setSegmentCount(0);
        entity.setCharacterCount(parsedResume.normalizedText().length());
        entity.setUploadedAt(LocalDateTime.now());
        resumeDocumentMapper.insert(entity);
        if (entity.getId() == null) {
            throw new IllegalStateException("保存简历主信息失败，未生成主键");
        }
        return entity;
    }

    private String resolveCandidateName(String candidateName, String fileName) {
        String normalized = normalizeKeyword(candidateName);
        if (!normalized.isBlank()) {
            return normalized;
        }
        String sanitized = sanitizeFileName(fileName);
        int dotIndex = sanitized.lastIndexOf('.');
        return dotIndex > 0 ? sanitized.substring(0, dotIndex) : sanitized;
    }

    private String normalizeCandidateNameKey(String candidateName) {
        String key = normalizeKeywordMatchText(candidateName);
        if (key.isBlank()) {
            return "unknown";
        }
        return key.length() > 128 ? key.substring(0, 128) : key;
    }

    private String storeOriginalResumeFile(String userIdKey, Long resumeId, String fileName, byte[] bytes) throws IOException {
        Path directory = resumeStorageDir.resolve(userIdKey).resolve(String.valueOf(resumeId));
        Files.createDirectories(directory);
        Path target = directory.resolve(UUID.randomUUID() + "-" + sanitizeFileName(fileName));
        Files.write(target, bytes);
        return target.toAbsolutePath().toString();
    }

    private String sanitizeFileName(String fileName) {
        String value = ResumeTextUtils.safe(fileName);
        if (value.isBlank()) {
            return "resume";
        }
        String normalized = value.replace('\\', '/');
        int slashIndex = normalized.lastIndexOf('/');
        String name = slashIndex >= 0 ? normalized.substring(slashIndex + 1) : normalized;
        return name.replaceAll("[\\r\\n\\t]+", "_").trim();
    }

    /**
     * 简历问答入口：
     * 先做意图判断，再决定是否执行简历 RAG 检索。
     */
    public QueryResumeResult queryResume(String userId, String query) {
        validateResumeQueryParams(userId, query);

        String traceId = UUID.randomUUID().toString();
        String normalizedUserId = ResumeTextUtils.normalizeUserId(userId);
        String userIdKey = ResumeTextUtils.toHexKey(normalizedUserId);
        List<TraceStep> steps = new ArrayList<>();

        QueryPreprocessing preprocessing = preprocessResumeQuery(query, steps);
        Intent intent = preprocessing.intent();
        QueryResumeExecution execution = answerResumeWithToolAgent(normalizedUserId, userIdKey, query, preprocessing, steps);
        ResumeQueryTrace trace = new ResumeQueryTrace(traceId, normalizedUserId, userIdKey, query, preprocessing.rewrittenQuery(), intent.name(), execution.totalElapsedMillis(), steps);
        resumeQueryTraceService.saveAsync(normalizedUserId, userIdKey, query, preprocessing.rewrittenQuery(), intent.name(), execution.answer(), trace);
        return new QueryResumeResult(execution.answer(), trace);
    }

    public void queryResumeStream(String userId,
                                  String query,
                                  Consumer<String> statusConsumer,
                                  Consumer<String> tokenConsumer,
                                  Consumer<ResumeQueryTrace> traceConsumer,
                                  Runnable completeCallback,
                                  Consumer<Throwable> errorConsumer) {
        validateResumeQueryParams(userId, query);

        String traceId = UUID.randomUUID().toString();
        String normalizedUserId = ResumeTextUtils.normalizeUserId(userId);
        String userIdKey = ResumeTextUtils.toHexKey(normalizedUserId);
        List<TraceStep> steps = new ArrayList<>();

        try {
            statusConsumer.accept("🧭 正在预处理查询...");
            QueryPreprocessing preprocessing = preprocessResumeQuery(query, steps);
            Intent intent = preprocessing.intent();

            String rewrittenQuery = preprocessing.rewrittenQuery();
            statusConsumer.accept("🧠 正在生成答案...");
            QueryResumeExecution execution = answerResumeWithToolAgent(normalizedUserId, userIdKey, query, preprocessing, steps);
            tokenConsumer.accept(execution.answer());
            ResumeQueryTrace trace = new ResumeQueryTrace(traceId, normalizedUserId, userIdKey, query, rewrittenQuery, intent.name(), execution.totalElapsedMillis(), steps);
            resumeQueryTraceService.saveAsync(normalizedUserId, userIdKey, query, rewrittenQuery, intent.name(), execution.answer(), trace);
            statusConsumer.accept("✅ 查询完成");
            traceConsumer.accept(trace);
            completeCallback.run();
        } catch (Exception e) {
            errorConsumer.accept(e);
        }
    }

    public ResumeDownload downloadResume(String userId, Long resumeId) throws IOException {
        if (resumeId == null || resumeId <= 0) {
            throw new IllegalArgumentException("resumeId 无效");
        }
        String normalizedUserId = ResumeTextUtils.normalizeUserId(userId);
        String userIdKey = ResumeTextUtils.toHexKey(normalizedUserId);
        ResumeDocumentEntity entity = resumeDocumentMapper.selectByIdAndUserIdKeyAndSourceType(resumeId, userIdKey, SOURCE_TYPE_RESUME);
        if (entity == null) {
            throw new IllegalArgumentException("未找到该简历或无权下载");
        }
        Path path = Path.of(ResumeTextUtils.safe(entity.getStoredFilePath()));
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new IOException("简历原文件不存在");
        }
        return new ResumeDownload(
                entity.getId(),
                entity.getCandidateName(),
                entity.getOriginalFileName(),
                entity.getContentType(),
                Files.readAllBytes(path)
        );
    }

    public List<ResumeListItem> listResumes(String userId) {
        String normalizedUserId = ResumeTextUtils.normalizeUserId(userId);
        String userIdKey = ResumeTextUtils.toHexKey(normalizedUserId);
        List<ResumeDocumentEntity> entities = resumeDocumentMapper.selectByUserIdKeyAndSourceType(userIdKey, SOURCE_TYPE_RESUME);
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }
        List<ResumeListItem> items = new ArrayList<>();
        for (ResumeDocumentEntity entity : entities) {
            items.add(new ResumeListItem(
                    entity.getId(),
                    entity.getCandidateName(),
                    entity.getOriginalFileName(),
                    entity.getContentType(),
                    entity.getSegmentCount() == null ? 0 : entity.getSegmentCount(),
                    entity.getCharacterCount() == null ? 0 : entity.getCharacterCount(),
                    entity.getUploadedAt() == null ? "" : entity.getUploadedAt().toString()
            ));
        }
        return items;
    }

    @Transactional(rollbackFor = Exception.class)
    public DeleteResumeResult deleteResume(String userId, Long resumeId) {
        if (resumeId == null || resumeId <= 0) {
            throw new IllegalArgumentException("resumeId 无效");
        }
        String normalizedUserId = ResumeTextUtils.normalizeUserId(userId);
        String userIdKey = ResumeTextUtils.toHexKey(normalizedUserId);
        ResumeDocumentEntity entity = resumeDocumentMapper.selectByIdAndUserIdKeyAndSourceType(resumeId, userIdKey, SOURCE_TYPE_RESUME);
        if (entity == null) {
            throw new IllegalArgumentException("未找到该候选人简历");
        }

        purgeRedisVectorsByResumeId(userIdKey, resumeId);
        resumeParentBlockMapper.deleteByResumeDocumentId(resumeId);
        int affected = resumeDocumentMapper.deleteByIdAndUserIdKeyAndSourceType(resumeId, userIdKey, SOURCE_TYPE_RESUME);
        tryDeleteStoredFile(entity.getStoredFilePath());

        return new DeleteResumeResult(
                normalizedUserId,
                resumeId,
                entity.getCandidateName(),
                affected > 0
        );
    }

    /**
     * 简历 RAG 检索问答：
     * 1) 先由 LLM 从问题中提取 metadata 约束；
     * 2) 动态构建 LangChain4j Filter 并执行向量/关键词混合召回；
     * 3) 将召回上下文交给模型生成最终答案。
     */
    private QueryResumeExecution answerResumeWithToolAgent(String normalizedUserId,
                                                           String userIdKey,
                                                           String originalQuery,
                                                           QueryPreprocessing preprocessing,
                                                           List<TraceStep> steps) {
        ResumeToolExecutionContext context = new ResumeToolExecutionContext(normalizedUserId, userIdKey, originalQuery, preprocessing, steps);
        resumeToolExecutionContext.set(context);
        String prompt = buildResumeToolAgentPrompt(originalQuery, preprocessing);
        try {
            TimedValue<String> answerStep = timeValue("tool_agent_chat_model", () -> resumeToolAssistant.answer(prompt));
            steps.add(new TraceStep(
                    "final_answer",
                    answerStep.elapsedMillis(),
                    estimateTokenCount(prompt, answerStep.value()),
                    traceData(
                            "promptCharacters", prompt.length(),
                            "mode", "tool_agent"
                    )
            ));
            return new QueryResumeExecution(answerStep.value(), sumElapsedMillis(steps));
        } finally {
            resumeToolExecutionContext.remove();
        }
    }

    private String buildResumeToolAgentPrompt(String originalQuery, QueryPreprocessing preprocessing) {
        return """
                用户原始问题：
                %s

                三合一预处理结果：
                - intent: %s
                - rewrittenQuery: %s
                - constraints: %s

                请基于用户问题作答。你可以自行决定是否调用工具、调用哪个工具、调用多少次以及每次检索多少上下文。
                如果需要简历证据，请优先用 rewrittenQuery 调用简历检索工具；如果要了解当前用户有哪些简历，可调用简历列表工具。
                """.formatted(
                ResumeTextUtils.safe(originalQuery),
                preprocessing.intent().name(),
                preprocessing.rewrittenQuery(),
                preprocessing.constraints()
        );
    }

    private QueryResumeExecution queryResumeWithRag(String normalizedUserId,
                                                    String originalQuery,
                                                    String retrievalQuery,
                                                    ResumeFilterConstraints constraints,
                                                    boolean horizontalCompare,
                                                    List<TraceStep> steps) {
        ResumeAnswerPreparation preparation = prepareResumeRagAnswer(normalizedUserId, originalQuery, retrievalQuery, constraints, horizontalCompare, steps, status -> {
        });
        return completeResumeAnswer(preparation, steps);
    }

    private QueryPreprocessing preprocessResumeQuery(String query, List<TraceStep> steps) {
        QueryPreprocessing fallback = new QueryPreprocessing(Intent.RESUME_QUERY, ResumeTextUtils.safe(query), ResumeFilterConstraints.empty());
        AsyncTimedValue<QueryPreprocessing> preprocessingStep = safeTimeValue(
                "query_preprocessing",
                () -> preprocessResumeQueryStrict(query),
                fallback
        );
        QueryPreprocessing preprocessing = preprocessingStep.value() == null ? fallback : preprocessingStep.value();
        String rewrittenQuery = normalizeRewrittenQuery(preprocessing.rewrittenQuery());
        if (rewrittenQuery.isBlank()) {
            rewrittenQuery = ResumeTextUtils.safe(query);
        }
        preprocessing = new QueryPreprocessing(
                preprocessing.intent() == null ? Intent.UNKNOWN : preprocessing.intent(),
                rewrittenQuery,
                preprocessing.constraints() == null ? ResumeFilterConstraints.empty() : preprocessing.constraints()
        );

        Map<String, Object> preprocessingData = traceData(
                "intent", preprocessing.intent().name(),
                "before", ResumeTextUtils.safe(query),
                "after", preprocessing.rewrittenQuery(),
                "constraints", preprocessing.constraints(),
                "fallbackUsed", !preprocessingStep.success()
        );
        addFailureTraceData(preprocessingData, preprocessingStep);
        steps.add(new TraceStep("query_preprocessing", preprocessingStep.elapsedMillis(), null, preprocessingData));

        return preprocessing;
    }

    private RetrievalResults retrieveResumeMatchesInParallel(String normalizedUserId,
                                                             String userIdKey,
                                                             String retrievalQuery,
                                                             ResumeFilterConstraints constraints,
                                                             int vectorMaxResults,
                                                             int keywordMaxResults) {
        CompletableFuture<VectorRetrievalResult> vectorFuture = CompletableFuture.supplyAsync(
                () -> retrieveVectorMatches(normalizedUserId, userIdKey, retrievalQuery, constraints, vectorMaxResults),
                aiTaskExecutor
        );
        CompletableFuture<KeywordRetrievalResult> keywordFuture = CompletableFuture.supplyAsync(
                () -> retrieveKeywordHits(normalizedUserId, retrievalQuery, keywordMaxResults),
                aiTaskExecutor
        );

        VectorRetrievalResult vectorResult = vectorFuture.join();
        KeywordRetrievalResult keywordResult = keywordFuture.join();
        List<TraceStep> retrievalSteps = new ArrayList<>();
        retrievalSteps.addAll(vectorResult.steps());
        retrievalSteps.addAll(keywordResult.steps());
        return new RetrievalResults(vectorResult.queryEmbedding(), vectorResult.vectorMatches(), keywordResult.keywordHits(), retrievalSteps);
    }

    private VectorRetrievalResult retrieveVectorMatches(String normalizedUserId,
                                                        String userIdKey,
                                                        String retrievalQuery,
                                                        ResumeFilterConstraints constraints,
                                                        int vectorMaxResults) {
        List<TraceStep> steps = new ArrayList<>();
        AsyncTimedValue<Response<Embedding>> embeddingStep = safeTimeValue("query_embedding", () -> embeddingModel.embed(retrievalQuery), null);
        Response<Embedding> queryEmbeddingResponse = embeddingStep.value();
        Embedding queryEmbedding = queryEmbeddingResponse == null ? null : queryEmbeddingResponse.content();
        Map<String, Object> embeddingData = traceData(
                "text", retrievalQuery,
                "async", true,
                "fallbackUsed", !embeddingStep.success()
        );
        addFailureTraceData(embeddingData, embeddingStep);
        steps.add(new TraceStep(
                "query_embedding",
                embeddingStep.elapsedMillis(),
                queryEmbeddingResponse == null || queryEmbeddingResponse.tokenUsage() == null ? null : queryEmbeddingResponse.tokenUsage().totalTokenCount(),
                embeddingData
        ));
        if (queryEmbedding == null) {
            steps.add(new TraceStep(
                    "vector_retrieval",
                    0L,
                    null,
                    traceData("topN", vectorMaxResults, "returned", 0, "matches", List.of(), "skipped", "query_embedding_failed")
            ));
            return new VectorRetrievalResult(queryEmbedding, List.of(), steps);
        }

        EmbeddingSearchRequest request = buildResumeSearchRequest(queryEmbedding, buildDynamicResumeFilter(normalizedUserId, constraints), vectorMaxResults, 0.7);
        AsyncTimedValue<List<EmbeddingMatch<TextSegment>>> vectorStep = safeTimeValue(
                "vector_retrieval",
                () -> searchVectorMatchesWithFallback(request, userIdKey, constraints),
                List.of()
        );
        List<EmbeddingMatch<TextSegment>> vectorMatches = vectorStep.value() == null ? List.of() : vectorStep.value();
        Map<String, Object> vectorData = traceData(
                "topN", vectorMaxResults,
                "returned", vectorMatches.size(),
                "matches", toVectorTraceItems(vectorMatches),
                "async", true,
                "fallbackUsed", !vectorStep.success()
        );
        addFailureTraceData(vectorData, vectorStep);
        steps.add(new TraceStep("vector_retrieval", vectorStep.elapsedMillis(), null, vectorData));
        return new VectorRetrievalResult(queryEmbedding, vectorMatches, steps);
    }

    private KeywordRetrievalResult retrieveKeywordHits(String normalizedUserId, String retrievalQuery, int keywordMaxResults) {
        AsyncTimedValue<List<KeywordHit>> keywordStep = safeTimeValue(
                "keyword_retrieval",
                () -> searchKeywordHits(normalizedUserId, retrievalQuery, keywordMaxResults),
                List.of()
        );
        List<KeywordHit> keywordHits = keywordStep.value() == null ? List.of() : keywordStep.value();
        Map<String, Object> keywordData = traceData(
                "topN", keywordMaxResults,
                "returned", keywordHits.size(),
                "hits", keywordHits,
                "async", true,
                "fallbackUsed", !keywordStep.success()
        );
        addFailureTraceData(keywordData, keywordStep);
        return new KeywordRetrievalResult(keywordHits, List.of(new TraceStep("keyword_retrieval", keywordStep.elapsedMillis(), null, keywordData)));
    }

    private ResumeAnswerPreparation prepareResumeRagAnswer(String normalizedUserId,
                                                           String originalQuery,
                                                           String retrievalQuery,
                                                           ResumeFilterConstraints constraints,
                                                           boolean horizontalCompare,
                                                           List<TraceStep> steps,
                                                           Consumer<String> statusConsumer) {
        StopWatch ragStopWatch = new StopWatch("resume-rag-query");
        ragStopWatch.start("total");
        int vectorMaxResults = horizontalCompare ? HORIZONTAL_COMPARE_VECTOR_MAX_RESULTS : RESUME_VECTOR_MAX_RESULTS;
        int keywordMaxResults = horizontalCompare ? HORIZONTAL_COMPARE_KEYWORD_MAX_RESULTS : RESUME_KEYWORD_MAX_RESULTS;
        int parentContextLimit = horizontalCompare ? HORIZONTAL_COMPARE_PARENT_CONTEXTS : RESUME_PARENT_CONTEXTS;
        int scoreCliffMinContexts = horizontalCompare ? HORIZONTAL_COMPARE_SCORE_CLIFF_MIN_CONTEXTS : SCORE_CLIFF_MIN_CONTEXTS;

        String userIdKey = ResumeTextUtils.toHexKey(normalizedUserId);

        statusConsumer.accept("📚 正在调用知识库...");
        statusConsumer.accept("📐 正在并行进行向量检索和关键词检索...");
        RetrievalResults retrievalResults = retrieveResumeMatchesInParallel(normalizedUserId, userIdKey, retrievalQuery, constraints, vectorMaxResults, keywordMaxResults);
        steps.addAll(retrievalResults.steps());
        List<EmbeddingMatch<TextSegment>> vectorMatches = retrievalResults.vectorMatches();
        List<KeywordHit> keywordHits = retrievalResults.keywordHits();

        List<EmbeddingMatch<TextSegment>> initialVectorMatches = vectorMatches;
        statusConsumer.accept("⚖️ 正在重排序候选片段...");
        TimedValue<HybridContextResult> hybridStep = timeValue("rerank_and_token_funnel", () -> collectHybridParentContexts(initialVectorMatches, keywordHits, retrievalQuery, constraints, parentContextLimit, scoreCliffMinContexts));
        HybridContextResult hybridResult = hybridStep.value();
        steps.add(new TraceStep("rerank_and_token_funnel", hybridStep.elapsedMillis(), null, hybridResult.traceData()));
        if (hybridResult.contexts().isEmpty()) {
            statusConsumer.accept("🔁 正在放宽条件重新检索...");
            Embedding queryEmbedding = retrievalResults.queryEmbedding();
            if (queryEmbedding == null) {
                String answer = "向量检索暂时不可用，未能完成简历片段召回。请稍后重试。";
                ragStopWatch.stop();
                return new ResumeAnswerPreparation("", answer, ragStopWatch.getTotalTimeMillis(), 0);
            }
            EmbeddingSearchRequest relaxedRequest = buildResumeSearchRequest(queryEmbedding, buildBaseResumeFilter(normalizedUserId), vectorMaxResults, 0.7);
            TimedValue<List<EmbeddingMatch<TextSegment>>> relaxedVectorStep = timeValue("vector_retrieval_relaxed", () -> searchVectorMatchesWithFallback(relaxedRequest, userIdKey, ResumeFilterConstraints.empty()));
            vectorMatches = relaxedVectorStep.value();
            steps.add(new TraceStep(
                    "vector_retrieval_relaxed",
                    relaxedVectorStep.elapsedMillis(),
                    null,
                    Map.of(
                            "topN", vectorMaxResults,
                            "returned", vectorMatches.size(),
                            "matches", toVectorTraceItems(vectorMatches)
                    )
            ));
            List<EmbeddingMatch<TextSegment>> relaxedVectorMatches = vectorMatches;
            TimedValue<HybridContextResult> relaxedHybridStep = timeValue("rerank_and_token_funnel_relaxed", () -> collectHybridParentContexts(relaxedVectorMatches, keywordHits, retrievalQuery, ResumeFilterConstraints.empty(), parentContextLimit, scoreCliffMinContexts));
            hybridResult = relaxedHybridStep.value();
            steps.add(new TraceStep("rerank_and_token_funnel_relaxed", relaxedHybridStep.elapsedMillis(), null, hybridResult.traceData()));
            if (hybridResult.contexts().isEmpty()) {
                String answer = "未检索到该用户的简历片段。请确认 userId 与上传时一致，并重新上传一次简历后再查询。";
                ragStopWatch.stop();
                return new ResumeAnswerPreparation("", answer, ragStopWatch.getTotalTimeMillis(), 0);
            }
        }

        String mergedContext = String.join("\n\n---\n\n", hybridResult.contexts());
        String outputInstruction = horizontalCompare ? """
                输出格式要求：
                1. 必须使用 Markdown 表格进行横向对比。
                2. 表格列必须包含候选人、核心结论、关键匹配点、风险或不足、证据、下载链接。
                3. 对比维度要对齐，同一维度在不同行中表达口径一致。
                4. 表格后给出结论：如果证据充分，明确指出谁更匹配并说明原因；如果证据不足，不要强行排序，明确说明缺少哪些信息以及当前只能得出的有限结论。
                5. 不要编造简历中没有的信息。
                """ : """
                输出格式要求：
                1. 禁止使用 Markdown 表格（不要使用 | --- | 这种格式）。
                2. 使用“编号列表 + 小标题”输出。
                3. 每位候选人按以下结构输出：
                   - 候选人：
                   - 结论：
                   - 证据：
                   - 下载链接：
                """;
        String prompt = """
                你是一个简历分析助手。
                你会收到按父块去重后的简历上下文，每个父块可能由多个子块检索命中后合并而来。
                每段上下文头部包含 resumeId、candidateName、fileName 和 downloadUrl。
                如果问题是在找候选人，请按候选人归纳输出，多个人都符合时列出多个人，并附上对应 downloadUrl。
                请优先理解 parentBlock 再回答，不要编造。
                %s
                如果简历中没有相关信息，请明确回答：未在该用户简历中找到相关信息。

                【简历上下文】
                %s

                【问题】
                %s
                """.formatted(outputInstruction, mergedContext, originalQuery);
        ragStopWatch.stop();
        return new ResumeAnswerPreparation(prompt, "", ragStopWatch.getTotalTimeMillis(), hybridResult.contexts().size());
    }

    private QueryPreprocessing preprocessResumeQueryStrict(String query) {
        String raw = metadataFilterAiService.preprocessResumeQuery(query);
        try {
            String json = normalizeJsonObject(raw);
            JsonNode node = objectMapper.readTree(json);
            Intent intent = IntentRoutingUtils.parseIntentLabel(node.path("intent").asText(""));
            String rewrittenQuery = normalizeRewrittenQuery(node.path("rewrittenQuery").asText(query));
            if (rewrittenQuery.isBlank()) {
                rewrittenQuery = ResumeTextUtils.safe(query);
            }
            ResumeFilterConstraints constraints = readResumeFilterConstraints(node.path("constraints"));
            return new QueryPreprocessing(intent, rewrittenQuery, constraints);
        } catch (Exception e) {
            return recoverPreprocessingFromText(query, raw);
        }
    }

    private QueryPreprocessing recoverPreprocessingFromText(String query, String raw) {
        String safeQuery = ResumeTextUtils.safe(query);
        Intent intent = extractIntentFromPreprocessingText(raw);
        String rewrittenQuery = normalizeRewrittenQuery(extractRewrittenQueryFromPreprocessingText(raw));
        if (rewrittenQuery.isBlank()) {
            rewrittenQuery = safeQuery;
        }
        return new QueryPreprocessing(intent, rewrittenQuery, ResumeFilterConstraints.empty());
    }

    private Intent extractIntentFromPreprocessingText(String raw) {
        Matcher matcher = PREPROCESS_INTENT_PATTERN.matcher(ResumeTextUtils.safe(raw));
        if (!matcher.find()) {
            return Intent.RESUME_QUERY;
        }
        return IntentRoutingUtils.parseIntentLabel(matcher.group(1));
    }

    private String extractRewrittenQueryFromPreprocessingText(String raw) {
        Matcher matcher = PREPROCESS_REWRITTEN_QUERY_PATTERN.matcher(ResumeTextUtils.safe(raw));
        if (!matcher.find()) {
            return "";
        }
        String value = ResumeTextUtils.safe(matcher.group(1));
        if (value.startsWith("\"") && value.endsWith("\"")) {
            try {
                return objectMapper.readTree(value).asText("");
            } catch (Exception ignored) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private QueryResumeExecution completeResumeAnswer(ResumeAnswerPreparation preparation, List<TraceStep> steps) {
        if (!preparation.immediateAnswer().isBlank()) {
            return new QueryResumeExecution(preparation.immediateAnswer(), preparation.preparationElapsedMillis() + sumElapsedMillis(steps));
        }
        TimedValue<String> answerStep = timeValue("chat_model", () -> chatLanguageModel.chat(preparation.prompt()));
        steps.add(new TraceStep(
                "final_answer",
                answerStep.elapsedMillis(),
                estimateTokenCount(preparation.prompt(), answerStep.value()),
                Map.of(
                        "promptCharacters", preparation.prompt().length(),
                        "contextCount", preparation.contextCount()
                )
        ));
        return new QueryResumeExecution(answerStep.value(), sumElapsedMillis(steps));
    }

    private void streamResumeAnswer(ResumeAnswerPreparation preparation,
                                    Consumer<String> tokenConsumer,
                                    Consumer<String> completeConsumer,
                                    Consumer<Throwable> errorConsumer,
                                    List<TraceStep> steps) {
        StopWatch stopWatch = new StopWatch();
        stopWatch.start("chat_model_stream");
        StringBuilder answer = new StringBuilder();
        streamingChatLanguageModel.chat(preparation.prompt(), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                String partial = ResumeTextUtils.safe(partialResponse);
                if (!partial.isBlank()) {
                    answer.append(partial);
                    tokenConsumer.accept(partial);
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                stopWatch.stop();
                Integer tokenCount = response == null || response.tokenUsage() == null
                        ? estimateTokenCount(preparation.prompt(), answer.toString())
                        : response.tokenUsage().totalTokenCount();
                steps.add(new TraceStep(
                        "final_answer",
                        stopWatch.getTotalTimeMillis(),
                        tokenCount,
                        Map.of(
                                "promptCharacters", preparation.prompt().length(),
                                "contextCount", preparation.contextCount(),
                                "mode", "stream"
                        )
                ));
                completeConsumer.accept(answer.toString());
            }

            @Override
            public void onError(Throwable error) {
                if (stopWatch.isRunning()) {
                    stopWatch.stop();
                }
                errorConsumer.accept(error);
            }
        });
    }

    private interface ResumeToolAssistant {
        @SystemMessage("""
                你是一个可以自主调用工具的简历分析助手。
                用户已经经过一次预处理，你会收到原始问题、重写后的检索问题、意图和约束信息。

                工具使用规则：
                1. 如果回答需要简历事实、候选人证据、项目/技能/教育/经历信息，必须调用简历检索工具。
                2. 你可以根据需要多次调用简历检索工具，例如分别检索不同候选人、不同技能或不同对比维度。
                3. 你可以自行决定每次检索多少上下文；横向对比、排序、多个候选人比较时应检索更多上下文。
                4. 如果只是闲聊或明显不需要简历数据，可以不调用检索工具，并引导用户提出简历相关问题。
                5. 不要编造工具结果中没有的信息。

                输出规则：
                1. 如果 intent 是 HORIZONTAL_COMPARE，必须使用 Markdown 表格横向对比，维度对齐，并在表格后给出明确结论；证据不足时说明缺口，不要强行排序。
                2. 其他简历查询不要使用 Markdown 表格，使用编号列表和小标题输出。
                3. 涉及候选人时尽量给出 downloadUrl。
                """)
        String answer(@UserMessage String userMessage);
    }

    private class ResumeSearchTools {

        @Tool("检索当前用户已上传的简历上下文。参数 query 是检索问题；maxParents 是希望返回的父块数量，普通查询建议 3 到 4，横向对比建议 6 到 8；usePreprocessedConstraints 表示是否使用预处理提取出的 metadata 约束。")
        public String searchResumeContexts(@P(value = "检索问题，优先使用预处理后的 rewrittenQuery，也可以为了多轮检索拆成更具体的子问题", required = true) String query,
                                           @P(value = "希望返回的父块数量，普通查询建议 3 到 4，横向对比建议 6 到 8", required = true) int maxParents,
                                           @P(value = "是否使用三合一预处理提取出的 metadata 约束；当你拆分了新子问题时可设为 false", required = true) boolean usePreprocessedConstraints) {
            ResumeToolExecutionContext context = currentResumeToolContext();
            String retrievalQuery = ResumeTextUtils.safe(query);
            if (retrievalQuery.isBlank()) {
                retrievalQuery = context.preprocessing().rewrittenQuery();
            }
            String finalRetrievalQuery = retrievalQuery;
            boolean horizontalCompare = isHorizontalCompareIntent(context.preprocessing().intent());
            int parentLimit = clamp(maxParents, 1, horizontalCompare ? HORIZONTAL_COMPARE_PARENT_CONTEXTS : RESUME_PARENT_CONTEXTS);
            int vectorMaxResults = horizontalCompare || parentLimit > RESUME_PARENT_CONTEXTS ? HORIZONTAL_COMPARE_VECTOR_MAX_RESULTS : RESUME_VECTOR_MAX_RESULTS;
            int keywordMaxResults = horizontalCompare || parentLimit > RESUME_PARENT_CONTEXTS ? HORIZONTAL_COMPARE_KEYWORD_MAX_RESULTS : RESUME_KEYWORD_MAX_RESULTS;
            int scoreCliffMinContexts = horizontalCompare ? HORIZONTAL_COMPARE_SCORE_CLIFF_MIN_CONTEXTS : SCORE_CLIFF_MIN_CONTEXTS;
            ResumeFilterConstraints constraints = usePreprocessedConstraints
                    ? context.preprocessing().constraints()
                    : ResumeFilterConstraints.empty();

            TimedValue<String> toolStep = timeValue("resume_tool_search", () -> searchResumeContextsForTool(
                    context.normalizedUserId(),
                    context.userIdKey(),
                    finalRetrievalQuery,
                    constraints,
                    vectorMaxResults,
                    keywordMaxResults,
                    parentLimit,
                    scoreCliffMinContexts
            ));
            context.steps().add(new TraceStep(
                    "resume_tool_search",
                    toolStep.elapsedMillis(),
                    estimateTokenCount("", toolStep.value()),
                    traceData(
                            "query", retrievalQuery,
                            "maxParents", parentLimit,
                            "usePreprocessedConstraints", usePreprocessedConstraints,
                            "returnedCharacters", toolStep.value().length()
                    )
            ));
            return toolStep.value();
        }

        @Tool("列出当前用户已上传的简历基础信息，用于先了解候选人池或确认有哪些简历。")
        public String listUploadedResumes(@P(value = "最多返回多少份简历基础信息", required = true) int limit) {
            ResumeToolExecutionContext context = currentResumeToolContext();
            int maxItems = clamp(limit, 1, 50);
            TimedValue<List<ResumeListItem>> listStep = timeValue("resume_tool_list_resumes", () -> listResumes(context.normalizedUserId()));
            List<ResumeListItem> items = listStep.value().stream().limit(maxItems).toList();
            context.steps().add(new TraceStep(
                    "resume_tool_list_resumes",
                    listStep.elapsedMillis(),
                    null,
                    traceData("limit", maxItems, "returned", items.size())
            ));
            if (items.isEmpty()) {
                return "当前用户没有已上传的简历。";
            }
            List<String> lines = new ArrayList<>();
            for (ResumeListItem item : items) {
                lines.add("- resumeId=" + item.resumeId()
                        + ", candidateName=" + item.candidateName()
                        + ", fileName=" + item.fileName()
                        + ", segmentCount=" + item.segmentCount()
                        + ", downloadUrl=/api/documents/resumes/" + item.resumeId() + "/download");
            }
            return String.join("\n", lines);
        }
    }

    private ResumeToolExecutionContext currentResumeToolContext() {
        ResumeToolExecutionContext context = resumeToolExecutionContext.get();
        if (context == null) {
            throw new IllegalStateException("Resume tool execution context is missing");
        }
        return context;
    }

    private String searchResumeContextsForTool(String normalizedUserId,
                                               String userIdKey,
                                               String retrievalQuery,
                                               ResumeFilterConstraints constraints,
                                               int vectorMaxResults,
                                               int keywordMaxResults,
                                               int parentContextLimit,
                                               int scoreCliffMinContexts) {
        RetrievalResults retrievalResults = retrieveResumeMatchesInParallel(normalizedUserId, userIdKey, retrievalQuery, constraints, vectorMaxResults, keywordMaxResults);
        ResumeToolExecutionContext context = currentResumeToolContext();
        context.steps().addAll(retrievalResults.steps());
        HybridContextResult hybridResult = collectHybridParentContexts(
                retrievalResults.vectorMatches(),
                retrievalResults.keywordHits(),
                retrievalQuery,
                constraints,
                parentContextLimit,
                scoreCliffMinContexts
        );
        context.steps().add(new TraceStep("resume_tool_rerank_and_token_funnel", 0L, null, hybridResult.traceData()));
        if (hybridResult.contexts().isEmpty() && retrievalResults.queryEmbedding() != null) {
            EmbeddingSearchRequest relaxedRequest = buildResumeSearchRequest(retrievalResults.queryEmbedding(), buildBaseResumeFilter(normalizedUserId), vectorMaxResults, 0.7);
            TimedValue<List<EmbeddingMatch<TextSegment>>> relaxedVectorStep = timeValue("resume_tool_vector_retrieval_relaxed", () -> searchVectorMatchesWithFallback(relaxedRequest, userIdKey, ResumeFilterConstraints.empty()));
            context.steps().add(new TraceStep(
                    "resume_tool_vector_retrieval_relaxed",
                    relaxedVectorStep.elapsedMillis(),
                    null,
                    traceData("topN", vectorMaxResults, "returned", relaxedVectorStep.value().size(), "matches", toVectorTraceItems(relaxedVectorStep.value()))
            ));
            hybridResult = collectHybridParentContexts(
                    relaxedVectorStep.value(),
                    retrievalResults.keywordHits(),
                    retrievalQuery,
                    ResumeFilterConstraints.empty(),
                    parentContextLimit,
                    scoreCliffMinContexts
            );
            context.steps().add(new TraceStep("resume_tool_rerank_and_token_funnel_relaxed", 0L, null, hybridResult.traceData()));
        }
        if (hybridResult.contexts().isEmpty()) {
            return "未检索到相关简历上下文。";
        }
        return String.join("\n\n---\n\n", hybridResult.contexts());
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
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
     * - 可选硬约束：由 LLM 提取的 fileName/contentType。
     * - parentType 只进入启发式评分，不作为硬过滤，避免“Spring 经验”这类问题误排除项目经历。
     * - 关键词 metadata 是一整串文本，Redis adapter 的 IsIn 不等价于 contains，
     *   因此技能/学校/公司等关键词只进入 JVM contains 判断和启发式重排。
     */
    private Filter buildDynamicResumeFilter(String normalizedUserId, ResumeFilterConstraints constraints) {
        List<Filter> filters = new ArrayList<>();
        filters.add(buildBaseResumeFilter(normalizedUserId));

        if (!constraints.fileName().isBlank()) {
            filters.add(metadataKey("fileName").isEqualTo(constraints.fileName()));
        }
        if (!constraints.contentType().isBlank()) {
            filters.add(metadataKey("contentType").isEqualTo(constraints.contentType()));
        }

        return mergeWithAnd(filters);
    }

    private Filter buildBaseResumeFilter(String normalizedUserId) {
        return new And(
                metadataKey("userIdKey").isEqualTo(ResumeTextUtils.toHexKey(normalizedUserId)),
                metadataKey("sourceType").isEqualTo(SOURCE_TYPE_RESUME)
        );
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

    private String normalizeRewrittenQuery(String raw) {
        String value = ResumeTextUtils.safe(raw)
                .replaceAll("(?i)^```[a-z]*", "")
                .replaceAll("```$", "")
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
        if ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'"))
                || (value.startsWith("“") && value.endsWith("”"))) {
            value = value.substring(1, value.length() - 1).trim();
        }
        if (value.length() > RESUME_QUERY_REWRITE_MAX_LENGTH) {
            value = value.substring(0, RESUME_QUERY_REWRITE_MAX_LENGTH).trim();
        }
        return value;
    }

    private String normalizeJsonObject(String raw) {
        String value = ResumeTextUtils.safe(raw)
                .replaceAll("(?i)^```json", "")
                .replaceAll("(?i)^```", "")
                .replaceAll("```$", "")
                .trim();
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start >= 0 && end >= start) {
            return value.substring(start, end + 1);
        }
        return value;
    }

    private ResumeFilterConstraints readResumeFilterConstraints(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return ResumeFilterConstraints.empty();
        }
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

    private boolean shouldUseResumeRag(Intent intent) {
        return intent == Intent.RESUME_QUERY || intent == Intent.HORIZONTAL_COMPARE;
    }

    private boolean isHorizontalCompareIntent(Intent intent) {
        return intent == Intent.HORIZONTAL_COMPARE;
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
                .maxResults(request.maxResults())
                .minScore(0.0)
                .build();
        return embeddingStore.search(fallbackRequest).matches().stream()
                .filter(match -> match.embedded() != null && match.embedded().metadata() != null)
                .filter(match -> metadataMatchesResumeFilter(match.embedded().metadata(), userIdKey, constraints))
                .limit(request.maxResults())
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
    private HybridContextResult collectHybridParentContexts(List<EmbeddingMatch<TextSegment>> vectorMatches,
                                                           List<KeywordHit> keywordHits,
                                                           String query,
                                                           ResumeFilterConstraints constraints,
                                                           int maxParents,
                                                           int scoreCliffMinContexts) {
        Map<String, ParentCandidate> candidates = new LinkedHashMap<>();
        //关键字信息
        HeuristicScoringContext scoringContext = buildHeuristicScoringContext(query, constraints);

        for (int i = 0; i < vectorMatches.size(); i++) {
            EmbeddingMatch<TextSegment> match = vectorMatches.get(i);
            TextSegment segment = match.embedded();
            if (segment == null) {
                continue;
            }
            Metadata metadata = segment.metadata();
            String parentIndex = metadata == null ? "" : ResumeTextUtils.safe(metadata.getString("parentIndex"));
            String parentType = metadata == null ? "" : ResumeTextUtils.safe(metadata.getString("parentType"));
            String resumeId = metadata == null ? "" : ResumeTextUtils.safe(metadata.getString("resumeId"));
            String candidateName = metadata == null ? "" : ResumeTextUtils.safe(metadata.getString("candidateName"));
            String fileName = metadata == null ? "" : ResumeTextUtils.safe(metadata.getString("fileName"));
            Long parentBlockId = parseParentBlockId(metadata == null ? "" : ResumeTextUtils.safe(metadata.getString("parentBlockId")));
            String metadataKeywords = metadata == null ? "" : collectMetadataKeywordText(metadata);
            String fallback = ResumeTextUtils.safe(segment.text());

            String parentKey = parentIndex.isBlank() ? "fallback-" + fallback.hashCode() : resumeId + ":" + parentIndex;
            String context = fallback;
            double score = weightedReciprocalRank(i, vectorRecallWeight);
            boolean keywordMatched = textOrMetadataMatchesKeywords(fallback, metadataKeywords, scoringContext);

            ParentCandidate incoming = new ParentCandidate(parentKey, parentType, resumeId, candidateName, fileName, metadataKeywords, parentBlockId, context, score, true, keywordMatched);
            ParentCandidate existing = candidates.get(parentKey);
            if (existing == null || incoming.score() > existing.score()) {
                candidates.put(parentKey, incoming);
            }
        }

        for (int i = 0; i < keywordHits.size(); i++) {
            KeywordHit hit = keywordHits.get(i);
            String parentKey = hit.parentIndex().isBlank() ? "kw-" + i : hit.resumeId() + ":" + hit.parentIndex();
            ParentCandidate existing = candidates.get(parentKey);
            double score = weightedReciprocalRank(i, keywordRecallWeight);
            Long keywordParentBlockId = parseParentBlockId(hit.parentBlockId());
            if (existing == null) {
                candidates.put(parentKey, new ParentCandidate(parentKey, hit.parentType(), hit.resumeId(), hit.candidateName(), hit.fileName(), "", keywordParentBlockId, "", score, false, true));
            } else {
                candidates.put(parentKey, new ParentCandidate(
                        existing.parentKey(),
                        existing.parentType().isBlank() ? hit.parentType() : existing.parentType(),
                        existing.resumeId().isBlank() ? hit.resumeId() : existing.resumeId(),
                        existing.candidateName().isBlank() ? hit.candidateName() : existing.candidateName(),
                        existing.fileName().isBlank() ? hit.fileName() : existing.fileName(),
                        existing.metadataKeywords(),
                        existing.parentBlockId() == null ? keywordParentBlockId : existing.parentBlockId(),
                        existing.context(),
                        existing.score() + score,
                        existing.vectorHit(),
                        true
                ));
            }
        }

        Map<Long, String> parentContentById = loadParentBlockContents(
                candidates.values().stream()
                        .map(ParentCandidate::parentBlockId)
                        .filter(id -> id != null && id > 0)
                        .toList()
        );

        List<ParentCandidate> scoredCandidates = candidates.values().stream()
                .map(candidate -> scoreParentCandidate(candidate, parentContentById, scoringContext))
                .toList();
        List<RankTraceItem> beforeRank = toRankTraceItems(candidates.values().stream().toList());
        boolean hasKeywordMatchedCandidate = scoredCandidates.stream().anyMatch(ParentCandidate::keywordHit);
        List<ParentCandidate> candidatesForAnswer = hasKeywordMatchedCandidate
                ? scoredCandidates.stream().filter(ParentCandidate::keywordHit).toList()
                : scoredCandidates;

        List<ParentCandidate> sortedCandidates = candidatesForAnswer.stream()
                .sorted(Comparator.comparingDouble(ParentCandidate::score).reversed())
                .toList();
        List<ParentCandidate> selectedCandidates = truncateAfterScoreCliff(sortedCandidates, maxParents, scoreCliffMinContexts);
        List<RankTraceItem> afterRank = toRankTraceItems(sortedCandidates);
        List<RankTraceItem> selectedRank = toRankTraceItems(selectedCandidates);

        List<String> contexts = selectedCandidates.stream()
                .map(candidate -> {
                    String type = candidate.parentType().isBlank() ? "resume" : candidate.parentType();
                    String dbContent = candidate.parentBlockId() == null ? "" : ResumeTextUtils.safe(parentContentById.get(candidate.parentBlockId()));
                    String content = dbContent.isBlank() ? candidate.context() : dbContent;
                    return "【resumeId=" + candidate.resumeId()
                            + ", candidateName=" + candidate.candidateName()
                            + ", fileName=" + candidate.fileName()
                            + ", downloadUrl=/api/documents/resumes/" + candidate.resumeId() + "/download"
                            + ", parentType=" + type
                            + ", parentIndex=" + candidate.parentKey() + "】\n" + content;
                })
                .toList();
        Map<String, Object> traceData = new LinkedHashMap<>();
        traceData.put("candidateCountBeforeRerank", candidates.size());
        traceData.put("candidateCountAfterKeywordGate", candidatesForAnswer.size());
        traceData.put("selectedCount", selectedCandidates.size());
        traceData.put("truncatedCount", Math.max(0, sortedCandidates.size() - selectedCandidates.size()));
        traceData.put("maxParents", maxParents);
        traceData.put("scoreCliffMinContexts", scoreCliffMinContexts);
        traceData.put("beforeRank", beforeRank);
        traceData.put("afterRank", afterRank);
        traceData.put("selected", selectedRank);
        return new HybridContextResult(contexts, traceData);
    }

    private List<VectorTraceItem> toVectorTraceItems(List<EmbeddingMatch<TextSegment>> vectorMatches) {
        if (vectorMatches == null || vectorMatches.isEmpty()) {
            return List.of();
        }
        List<VectorTraceItem> items = new ArrayList<>();
        for (int i = 0; i < vectorMatches.size(); i++) {
            EmbeddingMatch<TextSegment> match = vectorMatches.get(i);
            TextSegment segment = match.embedded();
            Metadata metadata = segment == null ? null : segment.metadata();
            items.add(new VectorTraceItem(
                    i + 1,
                    match.score(),
                    metadata == null ? "" : ResumeTextUtils.safe(metadata.getString("resumeId")),
                    metadata == null ? "" : ResumeTextUtils.safe(metadata.getString("candidateName")),
                    metadata == null ? "" : ResumeTextUtils.safe(metadata.getString("parentIndex")),
                    metadata == null ? "" : ResumeTextUtils.safe(metadata.getString("parentType")),
                    segment == null ? "" : limitText(ResumeTextUtils.safe(segment.text()), 160)
            ));
        }
        return items;
    }

    private List<RankTraceItem> toRankTraceItems(List<ParentCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<RankTraceItem> items = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            ParentCandidate candidate = candidates.get(i);
            items.add(new RankTraceItem(
                    i + 1,
                    candidate.parentKey(),
                    candidate.resumeId(),
                    candidate.candidateName(),
                    candidate.fileName(),
                    candidate.score(),
                    candidate.vectorHit(),
                    candidate.keywordHit()
            ));
        }
        return items;
    }

    private List<ParentCandidate> truncateAfterScoreCliff(List<ParentCandidate> sortedCandidates,
                                                          int maxParents,
                                                          int scoreCliffMinContexts) {
        if (sortedCandidates == null || sortedCandidates.isEmpty() || maxParents <= 0) {
            return List.of();
        }
        List<ParentCandidate> selected = new ArrayList<>();
        int upperBound = Math.min(maxParents, sortedCandidates.size());
        int minContexts = Math.max(1, scoreCliffMinContexts);
        for (int i = 0; i < upperBound; i++) {
            ParentCandidate current = sortedCandidates.get(i);
            if (i >= minContexts && isScoreCliff(sortedCandidates.get(i - 1).score(), current.score())) {
                break;
            }
            selected.add(current);
        }
        return selected;
    }

    private boolean isScoreCliff(double previousScore, double currentScore) {
        if (previousScore <= 0.0d) {
            return false;
        }
        double scoreGap = previousScore - currentScore;
        return scoreGap >= SCORE_CLIFF_MIN_GAP
                && currentScore <= previousScore * SCORE_CLIFF_RETAIN_RATIO;
    }

    private ParentCandidate scoreParentCandidate(ParentCandidate candidate,
                                                 Map<Long, String> parentContentById,
                                                 HeuristicScoringContext scoringContext) {
        String dbContent = candidate.parentBlockId() == null ? "" : ResumeTextUtils.safe(parentContentById.get(candidate.parentBlockId()));
        String content = dbContent.isBlank() ? candidate.context() : dbContent;
        double heuristicScore = heuristicScore(candidate, content, scoringContext);
        return new ParentCandidate(
                candidate.parentKey(),
                candidate.parentType(),
                candidate.resumeId(),
                candidate.candidateName(),
                candidate.fileName(),
                candidate.metadataKeywords(),
                candidate.parentBlockId(),
                candidate.context(),
                candidate.score() + heuristicScore,
                candidate.vectorHit(),
                candidate.keywordHit()
        );
    }

    private double heuristicScore(ParentCandidate candidate, String content, HeuristicScoringContext scoringContext) {
        String normalizedContent = normalizeKeywordMatchText(content);
        String normalizedMetadata = normalizeKeywordMatchText(candidate.metadataKeywords());
        String normalizedIdentity = normalizeKeywordMatchText(candidate.candidateName() + " " + candidate.fileName());

        double score = 0.0d;
        score += keywordHitScore(scoringContext.requiredKeywords(), normalizedContent, normalizedMetadata);
        score += queryTermHitScore(scoringContext.queryTerms(), normalizedContent, normalizedMetadata, normalizedIdentity);
        score += positionScore(scoringContext.requiredKeywords(), content);
        score += businessTagScore(scoringContext.businessTags(), normalizedContent, normalizedMetadata);
        score += parentTypeScore(scoringContext.constraints(), candidate.parentType());
        score += sourceBlendScore(candidate);
        return score;
    }

    private double keywordHitScore(List<String> keywords, String normalizedContent, String normalizedMetadata) {
        if (keywords.isEmpty()) {
            return 0.0d;
        }
        int matched = 0;
        for (String keyword : keywords) {
            String normalizedKeyword = normalizeKeywordMatchText(keyword);
            if (!normalizedKeyword.isBlank()
                    && (normalizedContent.contains(normalizedKeyword) || normalizedMetadata.contains(normalizedKeyword))) {
                matched++;
            }
        }
        return Math.min(0.9d, matched * 0.12d);
    }

    private double queryTermHitScore(List<String> queryTerms,
                                     String normalizedContent,
                                     String normalizedMetadata,
                                     String normalizedIdentity) {
        double score = 0.0d;
        for (String queryTerm : queryTerms) {
            String normalizedTerm = normalizeKeywordMatchText(queryTerm);
            if (normalizedTerm.isBlank()) {
                continue;
            }
            if (normalizedIdentity.contains(normalizedTerm)) {
                score += 0.18d;
            } else if (normalizedMetadata.contains(normalizedTerm)) {
                score += 0.1d;
            } else if (normalizedContent.contains(normalizedTerm)) {
                score += 0.06d;
            }
        }
        return Math.min(0.6d, score);
    }

    private double positionScore(List<String> keywords, String content) {
        if (keywords.isEmpty() || content == null || content.isBlank()) {
            return 0.0d;
        }
        String normalizedContent = normalizeKeywordMatchText(content);
        int bestIndex = Integer.MAX_VALUE;
        for (String keyword : keywords) {
            String normalizedKeyword = normalizeKeywordMatchText(keyword);
            if (normalizedKeyword.isBlank()) {
                continue;
            }
            int index = normalizedContent.indexOf(normalizedKeyword);
            if (index >= 0 && index < bestIndex) {
                bestIndex = index;
            }
        }
        if (bestIndex == Integer.MAX_VALUE) {
            return 0.0d;
        }
        double relativePosition = normalizedContent.isBlank() ? 1.0d : (double) bestIndex / Math.max(1, normalizedContent.length());
        return Math.max(0.0d, 0.22d * (1.0d - relativePosition));
    }

    private double businessTagScore(List<BusinessTagQuery> businessTags, String normalizedContent, String normalizedMetadata) {
        double score = 0.0d;
        for (BusinessTagQuery businessTag : businessTags) {
            boolean labelMatched = normalizedMetadata.contains(normalizeKeywordMatchText(businessTag.label()))
                    || normalizedContent.contains(normalizeKeywordMatchText(businessTag.label()));
            boolean signalMatched = false;
            for (String signal : businessTag.signals()) {
                String normalizedSignal = normalizeKeywordMatchText(signal);
                if (!normalizedSignal.isBlank()
                        && (normalizedMetadata.contains(normalizedSignal) || normalizedContent.contains(normalizedSignal))) {
                    signalMatched = true;
                    break;
                }
            }
            if (labelMatched || signalMatched) {
                score += 0.35d;
            }
        }
        return Math.min(0.7d, score);
    }

    private boolean textOrMetadataMatchesKeywords(String text,
                                                  String metadataKeywords,
                                                  HeuristicScoringContext scoringContext) {
        String normalizedText = normalizeKeywordMatchText(text);
        String normalizedMetadata = normalizeKeywordMatchText(metadataKeywords);
        for (String keyword : scoringContext.requiredKeywords()) {
            if (containsAnyExpandedKeyword(normalizedText, normalizedMetadata, expandSemanticQueryKeywords(keyword))) {
                return true;
            }
        }
        for (String queryTerm : scoringContext.queryTerms()) {
            if (containsAnyExpandedKeyword(normalizedText, normalizedMetadata, expandSemanticQueryKeywords(queryTerm))) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAnyExpandedKeyword(String normalizedText, String normalizedMetadata, List<String> keywords) {
        for (String keyword : keywords) {
            String normalizedKeyword = normalizeKeywordMatchText(keyword);
            if (!normalizedKeyword.isBlank()
                    && (normalizedText.contains(normalizedKeyword) || normalizedMetadata.contains(normalizedKeyword))) {
                return true;
            }
        }
        return false;
    }

    private double parentTypeScore(ResumeFilterConstraints constraints, String parentType) {
        if (constraints.parentType().isBlank()) {
            return 0.0d;
        }
        return constraints.parentType().equals(ResumeTextUtils.safe(parentType)) ? 0.18d : -0.12d;
    }

    private double sourceBlendScore(ParentCandidate candidate) {
        if (candidate.vectorHit() && candidate.keywordHit()) {
            return 0.25d;
        }
        if (candidate.keywordHit()) {
            return 0.08d;
        }
        return 0.0d;
    }

    private HeuristicScoringContext buildHeuristicScoringContext(String query, ResumeFilterConstraints constraints) {
        List<String> requiredKeywords = collectConstraintKeywords(constraints);
        return new HeuristicScoringContext(
                extractQueryTerms(query),
                requiredKeywords,
                buildBusinessTagQueries(requiredKeywords),
                constraints
        );
    }

    private List<String> collectConstraintKeywords(ResumeFilterConstraints constraints) {
        Set<String> keywords = new LinkedHashSet<>();
        keywords.addAll(constraints.skills());
        keywords.addAll(constraints.companies());
        keywords.addAll(constraints.schools());
        keywords.addAll(constraints.titles());
        keywords.addAll(constraints.projects());
        keywords.addAll(constraints.industries());
        keywords.addAll(constraints.keywords());
        return keywords.stream()
                .map(this::normalizeKeyword)
                .filter(keyword -> !keyword.isBlank())
                .toList();
    }

    private List<String> extractQueryTerms(String query) {
        String normalized = ResumeTextUtils.safe(query);
        if (normalized.isBlank()) {
            return List.of();
        }
        return QUERY_TOKEN_SPLITTER.splitAsStream(normalized)
                .map(this::normalizeKeyword)
                .filter(term -> term.length() >= 2)
                .distinct()
                .limit(20)
                .toList();
    }

    private List<BusinessTagQuery> buildBusinessTagQueries(List<String> keywords) {
        List<BusinessTagQuery> businessTags = new ArrayList<>();
        for (String keyword : keywords) {
            String normalizedKeyword = normalizeKeywordMatchText(keyword);
            if ("名校".equals(normalizedKeyword)) {
                businessTags.add(new BusinessTagQuery("名校", FAMOUS_SCHOOL_SIGNALS));
            } else if ("大厂".equals(normalizedKeyword)) {
                businessTags.add(new BusinessTagQuery("大厂", BIG_COMPANY_SIGNALS));
            }
        }
        return businessTags;
    }

    private String collectMetadataKeywordText(Metadata metadata) {
        if (metadata == null) {
            return "";
        }
        return String.join(" ",
                ResumeTextUtils.safe(metadata.getString("resumeKeywords")),
                ResumeTextUtils.safe(metadata.getString("skillKeywords")),
                ResumeTextUtils.safe(metadata.getString("companyKeywords")),
                ResumeTextUtils.safe(metadata.getString("schoolKeywords")),
                ResumeTextUtils.safe(metadata.getString("titleKeywords")),
                ResumeTextUtils.safe(metadata.getString("projectKeywords")),
                ResumeTextUtils.safe(metadata.getString("industryKeywords"))
        );
    }

    private List<KeywordHit> searchKeywordHits(String normalizedUserId, String query, int limit) {
        try {
            String userIdKey = ResumeTextUtils.toHexKey(normalizedUserId);
            String textClause = ResumeTextUtils.buildRedisTextClause(query);
            if (textClause.isBlank()) {
                return List.of();
            }
            String redisQuery = "@userIdKey:{" + userIdKey + "} @sourceType:{resume} ("
                    + "@text:(" + textClause + ")"
                    + " | @resumeKeywords:(" + textClause + ")"
                    + " | @skillKeywords:(" + textClause + ")"
                    + " | @companyKeywords:(" + textClause + ")"
                    + " | @schoolKeywords:(" + textClause + ")"
                    + " | @titleKeywords:(" + textClause + ")"
                    + " | @projectKeywords:(" + textClause + ")"
                    + " | @industryKeywords:(" + textClause + ")"
                    + ")";
            FTSearchParams params = FTSearchParams.searchParams()
                    .limit(0, limit)
                    .returnFields("parentType", "parentIndex", "parentBlockId", "resumeId", "candidateName", "fileName")
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
            return new KeywordHit("", "", "", "", "", "");
        }
        return new KeywordHit(
                ResumeTextUtils.safe(doc.getString("parentType")),
                ResumeTextUtils.safe(doc.getString("parentIndex")),
                ResumeTextUtils.safe(doc.getString("parentBlockId")),
                ResumeTextUtils.safe(doc.getString("resumeId")),
                ResumeTextUtils.safe(doc.getString("candidateName")),
                ResumeTextUtils.safe(doc.getString("fileName"))
        );
    }

    private Long saveParentBlock(String normalizedUserId,
                                 String userIdKey,
                                 Long resumeDocumentId,
                                 int parentIndex,
                                 ParentBlock parentBlock) {
        ResumeParentBlockEntity entity = new ResumeParentBlockEntity();
        entity.setUserId(normalizedUserId);
        entity.setUserIdKey(userIdKey);
        entity.setSourceType(SOURCE_TYPE_RESUME);
        entity.setResumeDocumentId(resumeDocumentId);
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
        resumeDocumentMapper.deleteByUserIdKeyAndSourceType(userIdKey, SOURCE_TYPE_RESUME);
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

    private void purgeRedisVectorsByResumeId(String userIdKey, Long resumeId) {
        String resumeIdText = String.valueOf(resumeId);
        while (true) {
            String redisQuery = "@userIdKey:{" + userIdKey + "} @sourceType:{resume} @resumeId:{" + resumeIdText + "}";
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

    private void deleteResumeDataByEntity(String userIdKey, ResumeDocumentEntity entity) {
        if (entity == null || entity.getId() == null) {
            return;
        }
        Long resumeId = entity.getId();
        purgeRedisVectorsByResumeId(userIdKey, resumeId);
        resumeParentBlockMapper.deleteByResumeDocumentId(resumeId);
        resumeDocumentMapper.deleteByIdAndUserIdKeyAndSourceType(resumeId, userIdKey, SOURCE_TYPE_RESUME);
        tryDeleteStoredFile(entity.getStoredFilePath());
    }

    private void tryDeleteStoredFile(String storedFilePath) {
        String path = ResumeTextUtils.safe(storedFilePath);
        if (path.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(Path.of(path));
        } catch (Exception ignored) {
            // no-op
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

    private <T> AsyncTimedValue<T> safeTimeValue(String taskName, Supplier<T> supplier, T fallbackValue) {
        StopWatch stopWatch = new StopWatch();
        stopWatch.start(taskName);
        try {
            T value = supplier.get();
            stopWatch.stop();
            return new AsyncTimedValue<>(value, stopWatch.getTotalTimeMillis(), true, "");
        } catch (Exception e) {
            stopWatch.stop();
            return new AsyncTimedValue<>(fallbackValue, stopWatch.getTotalTimeMillis(), false, formatError(e));
        }
    }

    private <T> TimedValue<T> timeValue(String taskName, Supplier<T> supplier) {
        StopWatch stopWatch = new StopWatch();
        stopWatch.start(taskName);
        T value;
        try {
            value = supplier.get();
        } finally {
            stopWatch.stop();
        }
        return new TimedValue<>(value, stopWatch.getTotalTimeMillis());
    }

    private Map<String, Object> traceData(Object... keyValues) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            data.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return data;
    }

    private void addFailureTraceData(Map<String, Object> data, AsyncTimedValue<?> step) {
        if (data == null || step == null || step.success()) {
            return;
        }
        data.put("error", step.error());
    }

    private String formatError(Exception e) {
        if (e == null) {
            return "";
        }
        String message = ResumeTextUtils.safe(e.getMessage());
        return message.isBlank() ? e.getClass().getSimpleName() : e.getClass().getSimpleName() + ": " + message;
    }

    private long sumElapsedMillis(List<TraceStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return 0L;
        }
        return steps.stream().mapToLong(TraceStep::elapsedMillis).sum();
    }

    private Integer estimateTokenCount(String prompt, String answer) {
        int chars = ResumeTextUtils.safe(prompt).length() + ResumeTextUtils.safe(answer).length();
        if (chars <= 0) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(chars / 3.2d));
    }

    private String limitText(String value, int limit) {
        String safe = ResumeTextUtils.safe(value);
        if (safe.length() <= limit) {
            return safe;
        }
        return safe.substring(0, limit);
    }

    private record TimedValue<T>(
            T value,
            long elapsedMillis
    ) {
    }

    private record AsyncTimedValue<T>(
            T value,
            long elapsedMillis,
            boolean success,
            String error
    ) {
    }

    private record QueryPreprocessing(
            Intent intent,
            String rewrittenQuery,
            ResumeFilterConstraints constraints
    ) {
    }

    private record ResumeToolExecutionContext(
            String normalizedUserId,
            String userIdKey,
            String originalQuery,
            QueryPreprocessing preprocessing,
            List<TraceStep> steps
    ) {
    }

    private record RetrievalResults(
            Embedding queryEmbedding,
            List<EmbeddingMatch<TextSegment>> vectorMatches,
            List<KeywordHit> keywordHits,
            List<TraceStep> steps
    ) {
    }

    private record VectorRetrievalResult(
            Embedding queryEmbedding,
            List<EmbeddingMatch<TextSegment>> vectorMatches,
            List<TraceStep> steps
    ) {
    }

    private record KeywordRetrievalResult(
            List<KeywordHit> keywordHits,
            List<TraceStep> steps
    ) {
    }

    private record QueryResumeExecution(
            String answer,
            long totalElapsedMillis
    ) {
    }

    private record ResumeAnswerPreparation(
            String prompt,
            String immediateAnswer,
            long preparationElapsedMillis,
            int contextCount
    ) {
    }

    private record HybridContextResult(
            List<String> contexts,
            Map<String, Object> traceData
    ) {
    }

    //父块
    private record ParentBlock(String type, String content) {
    }

    //父块候选内容
    private record ParentCandidate(
            String parentKey,
            String parentType,
            String resumeId,
            String candidateName,
            String fileName,
            String metadataKeywords,
            Long parentBlockId,
            String context,
            double score,
            boolean vectorHit,
            boolean keywordHit
    ) {
    }

    //启发式评分所需内容
    private record HeuristicScoringContext(
            List<String> queryTerms,
            List<String> requiredKeywords,
            List<BusinessTagQuery> businessTags,
            ResumeFilterConstraints constraints
    ) {
    }

    private record BusinessTagQuery(
            String label,
            List<String> signals
    ) {
    }

    private record KeywordHit(String parentType,
                              String parentIndex,
                              String parentBlockId,
                              String resumeId,
                              String candidateName,
                              String fileName) {
    }

    private record VectorTraceItem(
            int rank,
            double score,
            String resumeId,
            String candidateName,
            String parentIndex,
            String parentType,
            String textPreview
    ) {
    }

    private record RankTraceItem(
            int rank,
            String parentKey,
            String resumeId,
            String candidateName,
            String fileName,
            double score,
            boolean vectorHit,
            boolean keywordHit
    ) {
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
            Long resumeId,
            String candidateName,
            String fileName,
            int segmentCount,
            int characterCount,
            Integer embeddingTokenCount
    ) {
    }

    public record BatchUploadResult(
            String userId,
            List<UploadResult> uploaded,
            List<SkippedUpload> skipped
    ) {
    }

    public record SkippedUpload(
            String fileName,
            String reason
    ) {
    }

    public record ResumeDownload(
            Long resumeId,
            String candidateName,
            String fileName,
            String contentType,
            byte[] bytes
    ) {
    }

    public record ResumeListItem(
            Long resumeId,
            String candidateName,
            String fileName,
            String contentType,
            int segmentCount,
            int characterCount,
            String uploadedAt
    ) {
    }

    public record DeleteResumeResult(
            String userId,
            Long resumeId,
            String candidateName,
            boolean deleted
    ) {
    }

    public record QueryResumeResult(
            String answer,
            ResumeQueryTrace trace
    ) {
    }

    public record ResumeQueryTrace(
            String traceId,
            String userId,
            String userIdKey,
            String originalQuery,
            String rewrittenQuery,
            String intent,
            long totalElapsedMillis,
            List<TraceStep> steps
    ) {
    }

    public record TraceStep(
            String name,
            long elapsedMillis,
            Integer tokenCount,
            Map<String, Object> data
    ) {
    }

    private record UploadedResumeFile(
            String fileName,
            String contentType,
            byte[] bytes
    ) {
    }

    private record ParsedResume(
            String fileName,
            String contentType,
            byte[] bytes,
            Document document,
            String normalizedText,
            ResumeProfile profile
    ) {
    }

    private record ResumeProfile(
            boolean isResume,
            String candidateName
    ) {
    }

}
