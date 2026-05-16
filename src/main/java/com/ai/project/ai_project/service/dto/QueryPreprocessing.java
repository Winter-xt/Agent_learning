package com.ai.project.ai_project.service.dto;

import com.ai.project.ai_project.service.Intent;

/**
 * Tool Agent 执行前生成的三合一查询预处理结果。
 *
 * @param intent 用户问题意图
 * @param rewrittenQuery 由预处理 LLM 改写出的检索查询
 * @param constraints 从用户问题中提取出的 metadata 约束
 */
public record QueryPreprocessing(
        Intent intent,
        String rewrittenQuery,
        ResumeFilterConstraints constraints
) {
}
