package com.ai.project.ai_project.service;

import com.ai.project.ai_project.service.dto.QueryPreprocessing;
import com.ai.project.ai_project.service.dto.TraceStep;

import java.util.List;
import java.util.function.Consumer;

/**
 * 单次简历工具调用链路的运行时上下文。
 *
 * @param normalizedUserId 业务逻辑使用的规范化用户 ID
 * @param userIdKey Redis/MySQL metadata 过滤使用的十六进制用户 ID
 * @param originalQuery 预处理前的用户原始问题
 * @param preprocessing 三合一预处理结果，包含意图、重写查询和 metadata 约束
 * @param steps 当前查询执行共享的可变 trace 步骤列表
 * @param statusConsumer 当前查询执行共享的状态输出回调
 */
record ResumeToolExecutionContext(
        String normalizedUserId,
        String userIdKey,
        String originalQuery,
        QueryPreprocessing preprocessing,
        List<TraceStep> steps,
        Consumer<String> statusConsumer
) {
}
