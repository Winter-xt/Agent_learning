package com.ai.project.ai_project.util;

import com.ai.project.ai_project.service.Intent;

public final class IntentRoutingUtils {

    private IntentRoutingUtils() {
    }

    public static Intent parseIntentLabel(String label) {
        return Intent.from(label);
    }

    public static boolean shouldUseRag(Intent intent) {
        return intent == Intent.RESUME_QUERY || intent == Intent.HORIZONTAL_COMPARE;
    }
}
