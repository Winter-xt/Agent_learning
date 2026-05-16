package com.ai.project.ai_project.service.dto;

import java.util.Map;

/**
 * 简历查询 trace 中的单个可观测步骤。
 *
 * @param name 稳定的步骤名称
 * @param elapsedMillis 步骤耗时，单位毫秒
 * @param tokenCount 估算或模型供应商返回的 token 数量，可为空
 * @param data 用于调试和前端展示的结构化步骤数据
 */
public record TraceStep(
        String name,
        long elapsedMillis,
        Integer tokenCount,
        Map<String, Object> data
) {
}
