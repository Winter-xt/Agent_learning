package com.ai.project.ai_project.service.dto;

/**
 * 单份简历上传后的处理结果。
 *
 * @param userId 归属用户 ID
 * @param resumeId 简历主表 ID
 * @param candidateName 候选人姓名
 * @param fileName 原始文件名
 * @param segmentCount 写入向量库的子分片数量
 * @param characterCount 简历正文字符数
 * @param embeddingTokenCount embedding 模型返回的 token 数量，可为空
 */
public record UploadResult(
        String userId,
        Long resumeId,
        String candidateName,
        String fileName,
        int segmentCount,
        int characterCount,
        Integer embeddingTokenCount
) {
}
