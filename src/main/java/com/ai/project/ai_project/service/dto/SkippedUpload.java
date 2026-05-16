package com.ai.project.ai_project.service.dto;

/**
 * 批量上传时被跳过的文件信息。
 *
 * @param fileName 文件名
 * @param reason 跳过原因
 */
public record SkippedUpload(
        String fileName,
        String reason
) {
}
