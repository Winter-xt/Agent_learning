package com.ai.project.ai_project.service;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * LangChain4j 简历检索工具适配器。
 */
class ResumeSearchTools {

    private final DocumentLoader documentLoader;

    ResumeSearchTools(DocumentLoader documentLoader) {
        this.documentLoader = documentLoader;
    }

    @Tool("检索当前用户已上传的简历上下文。参数 query 是检索问题；maxParents 是希望返回的父块数量，普通查询建议 3 到 4，" +
            "横向对比建议 6 到 8；usePreprocessedConstraints 表示是否使用预处理提取出的 metadata 约束。" +
            "注意：参数 query 必须仅包含核心技术栈或岗位硬技能（如 'Java Spring'）， " +
            "禁止夹带候选人姓名、'专业技能'、'项目经验'、'寻找简历'等文档结构词或修饰性废话，否则会导致检索失效！")
    public String searchResumeContexts(@P(value = "检索问题，优先使用预处理后的 rewrittenQuery，也可以为了多轮检索拆成更具体的子问题", required = true) String query,
                                       @P(value = "希望返回的父块数量，普通查询建议 3 到 4，横向对比建议 6 到 8", required = true) int maxParents,
                                       @P(value = "是否使用三合一预处理提取出的 metadata 约束；当你拆分了新子问题时可设为 false", required = true) boolean usePreprocessedConstraints) {
        return documentLoader.searchResumeContextsFromTool(query, maxParents, usePreprocessedConstraints);
    }

    @Tool("列出当前用户已上传的简历基础信息，用于先了解候选人池或确认有哪些简历。")
    public String listUploadedResumes(@P(value = "最多返回多少份简历基础信息", required = true) int limit) {
        return documentLoader.listUploadedResumesFromTool(limit);
    }
}
