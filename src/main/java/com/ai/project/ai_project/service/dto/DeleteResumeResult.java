package com.ai.project.ai_project.service.dto;

/**
 * 删除单份简历后的结果。
 *
 * @param userId 归属用户 ID
 * @param resumeId 简历主表 ID
 * @param candidateName 候选人姓名
 * @param deleted 是否删除成功
 */
public record DeleteResumeResult(
        String userId,
        Long resumeId,
        String candidateName,
        boolean deleted
) {
}
