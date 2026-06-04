package com.ai.project.ai_project.service;

import com.ai.project.ai_project.service.dto.QueryPreprocessing;
import com.ai.project.ai_project.service.dto.QueryResumeResult;
import com.ai.project.ai_project.service.dto.ResumeFilterConstraints;
import com.ai.project.ai_project.service.dto.ResumeListItem;
import com.ai.project.ai_project.service.dto.ResumeQueryTrace;
import com.ai.project.ai_project.service.dto.TraceStep;
import com.ai.project.ai_project.util.IntentRoutingUtils;
import com.ai.project.ai_project.util.ResumeTextUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import org.springframework.util.StopWatch;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Spring AI 版简历检索问答链路。
 * <p>
 * 和 DocumentLoader 中的 LangChain4j Tool Agent 版本并行存在，便于对比：
 * - 预处理：Spring AI ChatClient，而不是 LangChain4j AiServices 接口代理。
 * - 工具：Spring AI @Tool，而不是 LangChain4j @Tool/@P。
 * - 检索：复用同一套向量/关键词混合召回，保证对比聚焦在编排框架差异。
 */
@Service
public class SpringAiResumeService {
    private static final int DEFAULT_PARENT_LIMIT = 4;
    private static final int COMPARE_PARENT_LIMIT = 8;
    private static final int RESUME_QUERY_REWRITE_MAX_LENGTH = 500;

    private static final String PREPROCESS_SYSTEM_PROMPT = """
            你是一个“简历查询预处理器”。
            请对用户问题一次性完成：意图识别、RAG 检索查询重写、metadata 约束提取，并严格按 JSON 输出。

            仅允许输出以下顶层字段：
            - intent: 仅可为 "RESUME_QUERY"、"HORIZONTAL_COMPARE"、"GENERAL_QA"、"CHITCHAT"、"UNKNOWN"
            - rewrittenQuery: 改写后的检索查询；无法或不需要改写时输出原问题
            - constraints: metadata 过滤条件对象

            rewrittenQuery 要保留原始意图，不要编造用户没有表达的技术栈、公司、学校、姓名或经历。
            constraints 仅允许包含 parentType、fileName、contentType、skills、companies、schools、titles、projects、industries、keywords。
            未提及的字符串字段输出空字符串，未提及的数组字段输出空数组。
            只输出一个 JSON 对象，不要输出 Markdown，不要解释。
            """;

    private static final String ANSWER_SYSTEM_PROMPT = """
            你是一个可以自主调用工具的简历分析助手。
            工具使用规则：
            1. 如果回答需要简历事实、候选人证据、项目/技能/教育/经历信息，必须调用简历检索工具。
            2. 你可以根据需要多次调用简历检索工具，例如分别检索不同候选人、不同技能或不同对比维度。
            3. 横向对比、排序、多个候选人比较时应检索更多上下文。
            4. 如果只是闲聊或明显不需要简历数据，可以不调用检索工具，并引导用户提出简历相关问题。
            5. 不要编造工具结果中没有的信息。

            输出规则：
            1. 如果 intent 是 HORIZONTAL_COMPARE，必须使用 Markdown 表格横向对比，维度对齐，并在表格后给出明确结论；证据不足时说明缺口，不要强行排序。
            2. 其他简历查询不要使用 Markdown 表格，使用编号列表和小标题输出。
            3. 涉及候选人时尽量给出 downloadUrl。
            """;

    private final ChatClient chatClient;
    private final DocumentLoader documentLoader;
    private final ResumeQueryTraceService resumeQueryTraceService;
    private final ObjectMapper objectMapper;

    public SpringAiResumeService(ChatClient.Builder chatClientBuilder,
                                 DocumentLoader documentLoader,
                                 ResumeQueryTraceService resumeQueryTraceService,
                                 ObjectMapper objectMapper) {
        this.chatClient = chatClientBuilder.build();
        this.documentLoader = documentLoader;
        this.resumeQueryTraceService = resumeQueryTraceService;
        this.objectMapper = objectMapper;
    }

    public QueryResumeResult queryResume(String userId, String query) {
        validateResumeQueryParams(userId, query);

        String traceId = UUID.randomUUID().toString();
        String normalizedUserId = ResumeTextUtils.normalizeUserId(userId);
        String userIdKey = ResumeTextUtils.toHexKey(normalizedUserId);
        List<TraceStep> steps = new ArrayList<>();

        QueryPreprocessing preprocessing = preprocessWithSpringAi(query, steps);
        QueryResumeExecution execution = answerWithSpringAiTools(normalizedUserId, query, preprocessing, steps, status -> {
        });

        ResumeQueryTrace trace = new ResumeQueryTrace(
                traceId,
                normalizedUserId,
                userIdKey,
                query,
                preprocessing.rewrittenQuery(),
                preprocessing.intent().name(),
                execution.totalElapsedMillis(),
                steps
        );
        resumeQueryTraceService.saveAsync(normalizedUserId, userIdKey, query, preprocessing.rewrittenQuery(), preprocessing.intent().name(), execution.answer(), trace);
        return new QueryResumeResult(execution.answer(), trace);
    }

    public void queryResumeStream(String userId,
                                  String query,
                                  Consumer<String> statusConsumer,
                                  Consumer<String> tokenConsumer,
                                  Consumer<ResumeQueryTrace> traceConsumer,
                                  Runnable completeCallback,
                                  Consumer<Throwable> errorConsumer) {
        try {
            validateResumeQueryParams(userId, query);

            String traceId = UUID.randomUUID().toString();
            String normalizedUserId = ResumeTextUtils.normalizeUserId(userId);
            String userIdKey = ResumeTextUtils.toHexKey(normalizedUserId);
            List<TraceStep> steps = new ArrayList<>();

            statusConsumer.accept("Spring AI: 正在预处理查询...");
            QueryPreprocessing preprocessing = preprocessWithSpringAi(query, steps);
            statusConsumer.accept("Spring AI: 正在生成答案...");
            QueryResumeExecution execution = answerWithSpringAiTools(normalizedUserId, query, preprocessing, steps, statusConsumer);

            tokenConsumer.accept(execution.answer());
            ResumeQueryTrace trace = new ResumeQueryTrace(
                    traceId,
                    normalizedUserId,
                    userIdKey,
                    query,
                    preprocessing.rewrittenQuery(),
                    preprocessing.intent().name(),
                    execution.totalElapsedMillis(),
                    steps
            );
            resumeQueryTraceService.saveAsync(normalizedUserId, userIdKey, query, preprocessing.rewrittenQuery(), preprocessing.intent().name(), execution.answer(), trace);
            statusConsumer.accept("Spring AI: 查询完成");
            traceConsumer.accept(trace);
            completeCallback.run();
        } catch (Exception e) {
            errorConsumer.accept(e);
        }
    }

    private QueryPreprocessing preprocessWithSpringAi(String query, List<TraceStep> steps) {
        StopWatch stopWatch = new StopWatch();
        stopWatch.start("spring_ai_query_preprocessing");
        String raw = "";
        boolean fallbackUsed = false;
        try {
            raw = chatClient.prompt()
                    .system(PREPROCESS_SYSTEM_PROMPT)
                    .user(query)
                    .call()
                    .content();
            QueryPreprocessing preprocessing = parsePreprocessing(query, raw);
            stopWatch.stop();
            steps.add(new TraceStep(
                    "spring_ai_query_preprocessing",
                    stopWatch.getTotalTimeMillis(),
                    estimateTokenCount(query, raw),
                    Map.of(
                            "intent", preprocessing.intent().name(),
                            "before", ResumeTextUtils.safe(query),
                            "after", preprocessing.rewrittenQuery(),
                            "constraints", preprocessing.constraints(),
                            "fallbackUsed", false
                    )
            ));
            return preprocessing;
        } catch (Exception e) {
            fallbackUsed = true;
            if (stopWatch.isRunning()) {
                stopWatch.stop();
            }
            QueryPreprocessing fallback = new QueryPreprocessing(Intent.RESUME_QUERY, ResumeTextUtils.safe(query), ResumeFilterConstraints.empty());
            steps.add(new TraceStep(
                    "spring_ai_query_preprocessing",
                    stopWatch.getTotalTimeMillis(),
                    estimateTokenCount(query, raw),
                    Map.of(
                            "intent", fallback.intent().name(),
                            "before", ResumeTextUtils.safe(query),
                            "after", fallback.rewrittenQuery(),
                            "constraints", fallback.constraints(),
                            "fallbackUsed", fallbackUsed,
                            "error", ResumeTextUtils.safe(e.getMessage())
                    )
            ));
            return fallback;
        }
    }

    private QueryResumeExecution answerWithSpringAiTools(String normalizedUserId,
                                                        String originalQuery,
                                                        QueryPreprocessing preprocessing,
                                                        List<TraceStep> steps,
                                                        Consumer<String> statusConsumer) {
        StopWatch stopWatch = new StopWatch();
        stopWatch.start("spring_ai_tool_agent_chat_client");
        String prompt = buildSpringAiToolAgentPrompt(originalQuery, preprocessing);
        SpringAiResumeTools tools = new SpringAiResumeTools(documentLoader, normalizedUserId, preprocessing, steps, statusConsumer);
        String answer = chatClient.prompt()
                .system(ANSWER_SYSTEM_PROMPT)
                .user(prompt)
                .tools(tools)
                .call()
                .content();
        stopWatch.stop();
        steps.add(new TraceStep(
                "spring_ai_final_answer",
                stopWatch.getTotalTimeMillis(),
                estimateTokenCount(prompt, answer),
                Map.of(
                        "promptCharacters", prompt.length(),
                        "mode", "spring_ai_tool_agent"
                )
        ));
        return new QueryResumeExecution(answer, sumElapsedMillis(steps));
    }

    private String buildSpringAiToolAgentPrompt(String originalQuery, QueryPreprocessing preprocessing) {
        return """
                用户原始问题：
                %s

                Spring AI 预处理结果：
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

    private QueryPreprocessing parsePreprocessing(String query, String raw) throws Exception {
        String json = normalizeJsonObject(raw);
        JsonNode node = objectMapper.readTree(json);
        Intent intent = IntentRoutingUtils.parseIntentLabel(node.path("intent").asText(""));
        String rewrittenQuery = normalizeRewrittenQuery(node.path("rewrittenQuery").asText(query));
        if (rewrittenQuery.isBlank()) {
            rewrittenQuery = ResumeTextUtils.safe(query);
        }
        return new QueryPreprocessing(intent, rewrittenQuery, readResumeFilterConstraints(node.path("constraints")));
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

    private ResumeFilterConstraints readResumeFilterConstraints(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return ResumeFilterConstraints.empty();
        }
        return new ResumeFilterConstraints(
                normalizeParentType(node.path("parentType").asText("")),
                ResumeTextUtils.safe(node.path("fileName").asText("")),
                ResumeTextUtils.safe(node.path("contentType").asText("")),
                readKeywordArray(node, "skills"),
                readKeywordArray(node, "companies"),
                readKeywordArray(node, "schools"),
                readKeywordArray(node, "titles"),
                readKeywordArray(node, "projects"),
                readKeywordArray(node, "industries"),
                readKeywordArray(node, "keywords")
        );
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

    private String normalizeParentType(String raw) {
        String value = ResumeTextUtils.safe(raw).toLowerCase(Locale.ROOT);
        if ("project".equals(value) || "resume".equals(value)) {
            return value;
        }
        return "";
    }

    private void validateResumeQueryParams(String userId, String query) {
        if (ResumeTextUtils.safe(userId).isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (ResumeTextUtils.safe(query).isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
    }

    private long sumElapsedMillis(List<TraceStep> steps) {
        return steps.stream().mapToLong(TraceStep::elapsedMillis).sum();
    }

    private int estimateTokenCount(String prompt, String answer) {
        return Math.max(1, (ResumeTextUtils.safe(prompt).length() + ResumeTextUtils.safe(answer).length()) / 2);
    }

    private record QueryResumeExecution(String answer, long totalElapsedMillis) {
    }

    static final class SpringAiResumeTools {
        private final DocumentLoader documentLoader;
        private final String normalizedUserId;
        private final QueryPreprocessing preprocessing;
        private final List<TraceStep> steps;
        private final Consumer<String> statusConsumer;

        private SpringAiResumeTools(DocumentLoader documentLoader,
                                    String normalizedUserId,
                                    QueryPreprocessing preprocessing,
                                    List<TraceStep> steps,
                                    Consumer<String> statusConsumer) {
            this.documentLoader = documentLoader;
            this.normalizedUserId = normalizedUserId;
            this.preprocessing = preprocessing;
            this.steps = steps;
            this.statusConsumer = statusConsumer;
        }

        @Tool(name = "spring_ai_resume_rag_search", description = "检索当前用户已上传简历的 RAG 上下文。适合回答候选人技能、项目、经历、教育背景、横向对比等问题。")
        public String searchResumeContexts(
                @ToolParam(description = "检索问题，优先使用预处理后的 rewrittenQuery，也可以为了多轮检索拆成更具体的子问题。") String query,
                @ToolParam(description = "返回父块数量；普通查询建议 3 到 4，横向对比建议 6 到 8。") int maxParents,
                @ToolParam(description = "是否使用 Spring AI 预处理提取出的 metadata 约束；拆分子问题检索时建议传 false。") boolean usePreprocessedConstraints) {
            String retrievalQuery = ResumeTextUtils.safe(query);
            if (retrievalQuery.isBlank()) {
                retrievalQuery = preprocessing.rewrittenQuery();
            }
            int fallbackLimit = preprocessing.intent() == Intent.HORIZONTAL_COMPARE ? COMPARE_PARENT_LIMIT : DEFAULT_PARENT_LIMIT;
            int parentLimit = Math.max(1, Math.min(maxParents <= 0 ? fallbackLimit : maxParents, COMPARE_PARENT_LIMIT));
            return documentLoader.searchResumeContextsFromSpringAi(
                    normalizedUserId,
                    retrievalQuery,
                    parentLimit,
                    usePreprocessedConstraints,
                    preprocessing,
                    steps,
                    statusConsumer
            );
        }

        @Tool(name = "spring_ai_resume_list_uploaded", description = "列出当前用户已上传的简历基础信息，用于确认候选人池、resumeId 和下载链接。")
        public String listUploadedResumes(@ToolParam(description = "最多返回多少份简历，建议 5 到 20。") int limit) {
            int maxItems = Math.max(1, Math.min(limit, 50));
            List<ResumeListItem> items = documentLoader.listResumes(normalizedUserId).stream()
                    .limit(maxItems)
                    .toList();
            steps.add(new TraceStep(
                    "spring_ai_resume_tool_list_resumes",
                    0L,
                    null,
                    Map.of("limit", maxItems, "returned", items.size())
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
}
