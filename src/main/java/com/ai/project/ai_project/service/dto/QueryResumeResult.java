package com.ai.project.ai_project.service.dto;

/**
 * 简历查询的最终结果。
 *
 * @param answer 返回给用户的回答正文
 * @param trace 本次查询链路的可观测 trace
 */
public record QueryResumeResult(
        String answer,
        ResumeQueryTrace trace
) {
}
