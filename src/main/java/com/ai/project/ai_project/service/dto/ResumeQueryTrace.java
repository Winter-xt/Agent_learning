package com.ai.project.ai_project.service.dto;

import java.util.List;

/**
 * 简历查询链路的完整 trace。
 *
 * @param traceId 单次查询 trace ID
 * @param userId 归属用户 ID
 * @param userIdKey 十六进制用户 ID
 * @param originalQuery 用户原始问题
 * @param rewrittenQuery 预处理后的检索查询
 * @param intent 识别出的用户意图
 * @param totalElapsedMillis 总耗时，单位毫秒
 * @param steps 查询链路中的步骤明细
 */
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
