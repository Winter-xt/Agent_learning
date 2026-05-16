package com.ai.project.ai_project.service;

import com.ai.project.ai_project.util.IntentRoutingUtils;
import com.ai.project.ai_project.service.dto.QueryPreprocessing;
import com.ai.project.ai_project.service.dto.ResumeFilterConstraints;
import com.ai.project.ai_project.util.ResumeTextUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 简历查询三合一预处理服务：意图识别、查询重写和 metadata 约束提取。
 */
@Service
class ResumePreprocessingService {
    private static final Logger log = LoggerFactory.getLogger(ResumePreprocessingService.class);

    private static final int RESUME_QUERY_REWRITE_MAX_LENGTH = 500;
    private static final Pattern PREPROCESS_INTENT_PATTERN = Pattern.compile("\"?intent\"?\\s*[:：]\\s*\"?([A-Z_]+)\"?", Pattern.CASE_INSENSITIVE);
    private static final Pattern PREPROCESS_REWRITTEN_QUERY_PATTERN = Pattern.compile("\"?rewrittenQuery\"?\\s*[:：]\\s*(\"(?:\\\\.|[^\"])*\"|[^,}\\r\\n]+)", Pattern.CASE_INSENSITIVE);

    private final ResumeMetadataFilterAiService metadataFilterAiService;
    private final ObjectMapper objectMapper;

    ResumePreprocessingService(ResumeMetadataFilterAiService metadataFilterAiService, ObjectMapper objectMapper) {
        this.metadataFilterAiService = metadataFilterAiService;
        this.objectMapper = objectMapper;
    }

    QueryPreprocessing preprocess(String query) {
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

    String normalizeRewrittenQuery(String raw) {
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
            } catch (Exception e) {
                log.debug("解析 rewrittenQuery 字符串失败，回退到去除外层引号", e);
                return value.substring(1, value.length() - 1);
            }
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
}
