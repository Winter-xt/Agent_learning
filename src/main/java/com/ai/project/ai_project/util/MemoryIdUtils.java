package com.ai.project.ai_project.util;

public final class MemoryIdUtils {

    private static final String DEFAULT_USER_ID = "default-user";

    private MemoryIdUtils() {
    }

    public static String buildScopedMemoryId(String category, String separator, String userId) {
        String safeCategory = isBlank(category) ? "default" : category;
        String safeUserId = isBlank(userId) ? DEFAULT_USER_ID : userId;
        return safeCategory + separator + safeUserId;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
