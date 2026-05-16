package com.ai.project.ai_project.service.dto;

/**
 * 简历原文件下载内容。
 *
 * @param resumeId 简历主表 ID
 * @param candidateName 候选人姓名
 * @param fileName 原始文件名
 * @param contentType 文件 MIME 类型
 * @param bytes 文件二进制内容
 */
public record ResumeDownload(
        Long resumeId,
        String candidateName,
        String fileName,
        String contentType,
        byte[] bytes
) {
}
