package com.ai.project.ai_project.service.dto;

import java.util.List;

/**
 * 批量上传简历后的处理结果。
 *
 * @param userId 归属用户 ID
 * @param uploaded 成功写入的简历列表
 * @param skipped 被跳过的文件及原因
 */
public record BatchUploadResult(
        String userId,
        List<UploadResult> uploaded,
        List<SkippedUpload> skipped
) {
}
