package com.ai.project.ai_project.service;

import com.ai.project.ai_project.service.dto.QueryResumeResult;
import com.ai.project.ai_project.service.dto.ResumeListItem;
import com.ai.project.ai_project.util.ResumeTextUtils;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 通过 Spring AI 暴露给 MCP Client 的简历 RAG 工具。
 */
@Service
public class ResumeMcpTools {

    private final DocumentLoader documentLoader;

    public ResumeMcpTools(DocumentLoader documentLoader) {
        this.documentLoader = documentLoader;
    }

    /**
     * 检索简历 RAG 上下文，适合 MCP Client 自己组织最终回答。
     *
     * @param userId 用户 ID，不传业务用户时可使用 default-user
     * @param query 检索问题或已经改写后的检索词
     * @param maxParents 希望返回的父块数量
     * @param usePreprocessedConstraints 是否使用服务端三合一预处理提取的 metadata 约束
     * @return 可直接交给 LLM 使用的简历上下文
     */
    @Tool(name = "resume_rag_search", description = "检索指定用户已上传简历的 RAG 上下文。适合回答候选人技能、项目、经历、教育背景、横向对比等问题。")
    public String searchResumeRagContexts(
            @ToolParam(description = "用户 ID；如果调用方没有业务用户概念，传 default-user。") String userId,
            @ToolParam(description = "检索问题，建议只包含核心岗位、技能、项目或经历关键词。") String query,
            @ToolParam(description = "返回父块数量；普通查询建议 3 到 4，横向对比建议 6 到 8。") int maxParents,
            @ToolParam(description = "是否使用服务端预处理提取的 metadata 约束；拆分子问题检索时建议传 false。") boolean usePreprocessedConstraints) {
        return documentLoader.searchResumeContextsFromMcp(userId, query, maxParents, usePreprocessedConstraints);
    }

    /**
     * 执行完整简历问答，由服务端完成预处理、检索、工具调用和最终答案生成。
     *
     * @param userId 用户 ID，不传业务用户时可使用 default-user
     * @param query 用户原始问题
     * @return 简历问答结果
     */
    @Tool(name = "resume_answer_question", description = "对指定用户的简历库进行完整问答，服务端会自动完成意图识别、查询重写、RAG 检索和答案生成。")
    public String answerResumeQuestion(
            @ToolParam(description = "用户 ID；如果调用方没有业务用户概念，传 default-user。") String userId,
            @ToolParam(description = "用户关于简历、候选人、技能、项目经历或横向对比的原始问题。") String query) {
        QueryResumeResult result = documentLoader.queryResume(userId, query);
        return result.answer();
    }

    /**
     * 列出已上传简历，适合 MCP Client 在检索前了解候选人池。
     *
     * @param userId 用户 ID，不传业务用户时可使用 default-user
     * @param limit 最多返回数量
     * @return 简历列表文本
     */
    @Tool(name = "resume_list_uploaded", description = "列出指定用户已上传的简历基础信息，用于确认候选人池、resumeId 和下载链接。")
    public String listUploadedResumes(
            @ToolParam(description = "用户 ID；如果调用方没有业务用户概念，传 default-user。") String userId,
            @ToolParam(description = "最多返回多少份简历，建议 5 到 20。") int limit) {
        String normalizedUserId = ResumeTextUtils.normalizeUserId(userId);
        int maxItems = Math.max(1, Math.min(limit, 50));
        List<ResumeListItem> items = documentLoader.listResumes(normalizedUserId).stream()
                .limit(maxItems)
                .toList();
        if (items.isEmpty()) {
            return "当前用户没有已上传的简历。";
        }
        StringBuilder builder = new StringBuilder();
        for (ResumeListItem item : items) {
            builder.append("- resumeId=").append(item.resumeId())
                    .append(", candidateName=").append(item.candidateName())
                    .append(", fileName=").append(item.fileName())
                    .append(", segmentCount=").append(item.segmentCount())
                    .append(", characterCount=").append(item.characterCount())
                    .append(", uploadedAt=").append(item.uploadedAt())
                    .append(", downloadUrl=/api/documents/resumes/")
                    .append(item.resumeId())
                    .append("/download\n");
        }
        return builder.toString().trim();
    }
}
