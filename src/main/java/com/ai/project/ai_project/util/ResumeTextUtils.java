package com.ai.project.ai_project.util;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ResumeTextUtils {

    private static final Pattern PROJECT_SECTION_HEADER = Pattern.compile(
            "^(项目经历|项目经验|项目背景)(\\s*[A-Za-z0-9一二三四五六七八九十]+)?\\s*[:：]?$"
    );
    private static final Pattern GENERIC_SECTION_HEADER = Pattern.compile(
            "^(基本信息|个人信息|联系方式|教育经历|工作经历|实习经历|项目经历|项目经验|专业技能|技能栈|技术栈|证书|获奖情况|自我评价|个人评价|个人总结)(\\s*[A-Za-z0-9一二三四五六七八九十]+)?\\s*[:：]?$"
    );
    private static final Pattern DATE_RANGE_PATTERN = Pattern.compile(
            "(19|20)\\d{2}[./-]\\d{1,2}\\s*[-~到至]\\s*((19|20)\\d{2}[./-]\\d{1,2}|至今|现在)"
    );
    private static final Pattern TOKEN_SPLITTER = Pattern.compile("[\\s,，。！？；;:：()（）\\-_/\\.]+");
    private static final Pattern ASCII_WORD_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9+#.]{1,}");
    private static final Pattern REDIS_ESCAPE_PATTERN = Pattern.compile("([\\\\@{}\\[\\]\\(\\)\\|\\-!~\"'])");

    private ResumeTextUtils() {
    }

    public static String normalizeText(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n').replaceAll("\n{3,}", "\n\n").trim();
    }

    public static boolean isSectionHeader(String line) {
        if (line == null || line.isBlank() || line.length() > 50) {
            return false;
        }
        return GENERIC_SECTION_HEADER.matcher(line).matches();
    }

    public static String resolveParentType(String headerType, String content) {
        if ("project".equals(headerType)) {
            return "project";
        }
        return looksLikeProjectContent(content) ? "project" : "resume";
    }

    public static boolean isProjectHeader(String line) {
        return line != null && PROJECT_SECTION_HEADER.matcher(line).matches();
    }

    public static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public static String normalizeUserId(String userId) {
        return userId == null || userId.isBlank() ? "default-user" : userId.trim();
    }

    public static String toHexKey(String raw) {
        String value = raw == null ? "" : raw;
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static String buildRedisTextClause(String query) {
        String normalized = safe(query);
        if (normalized.isBlank()) {
            return "";
        }
        List<String> tokens = TOKEN_SPLITTER
                .splitAsStream(normalized)
                .filter(token -> !token.isBlank())
                .toList();
        if (tokens.isEmpty()) {
            return "";
        }

        Set<String> clauses = new LinkedHashSet<>();
        String escapedWhole = REDIS_ESCAPE_PATTERN.matcher(normalized).replaceAll("\\\\$1");
        if (!escapedWhole.isBlank()) {
            clauses.add("\"" + escapedWhole + "\"");
        }

        for (String rawToken : tokens) {
            String token = rawToken.toLowerCase(Locale.ROOT).trim();
            if (token.isBlank()) {
                continue;
            }
            String escaped = REDIS_ESCAPE_PATTERN.matcher(token).replaceAll("\\\\$1");
            clauses.add(escaped);
            addAsciiWordClauses(clauses, token);
            if (containsHan(token) && token.length() >= 2) {
                clauses.add(escaped + "*");
            }
        }
        return clauses.isEmpty() ? "" : String.join(" | ", clauses);
    }

    private static void addAsciiWordClauses(Set<String> clauses, String token) {
        Matcher matcher = ASCII_WORD_PATTERN.matcher(token);
        while (matcher.find()) {
            String word = matcher.group().toLowerCase(Locale.ROOT).trim();
            if (word.length() < 2 || word.equals(token)) {
                continue;
            }
            String escaped = REDIS_ESCAPE_PATTERN.matcher(word).replaceAll("\\\\$1");
            clauses.add(escaped);
            clauses.add(escaped + "*");
        }
    }

    private static boolean looksLikeProjectContent(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        boolean hasDateRange = DATE_RANGE_PATTERN.matcher(content).find();
        boolean hasProjectNouns = containsAny(content, "项目", "系统", "平台", "业务", "架构");
        boolean hasWorkSignals = containsAny(content, "负责", "主导", "实现", "优化", "设计", "技术方案", "性能");
        return (hasDateRange && hasProjectNouns) || (hasProjectNouns && hasWorkSignals);
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsHan(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            Character.UnicodeScript script = Character.UnicodeScript.of(text.charAt(i));
            if (script == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }
}
