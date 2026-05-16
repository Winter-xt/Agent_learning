package com.ai.project.ai_project.service.dto;

/**
 * 简历列表中的单条摘要信息。
 *
 * @param resumeId 简历主表 ID
 * @param candidateName 候选人姓名
 * @param fileName 原始文件名
 * @param contentType 文件 MIME 类型
 * @param segmentCount 简历子分片数量
 * @param characterCount 简历正文字符数
 * @param uploadedAt 上传时间字符串
 */
public record ResumeListItem(
        Long resumeId,
        String candidateName,
        String fileName,
        String contentType,
        int segmentCount,
        int characterCount,
        String uploadedAt
) {
}
