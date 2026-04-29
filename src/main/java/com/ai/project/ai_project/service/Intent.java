package com.ai.project.ai_project.service;

/**
 * 用户问题意图枚举。
 * <p>
 * 该枚举用于在“问题进入业务处理前”进行统一语义分类，
 * 便于上层服务基于意图做动态路由（例如：是否进入 RAG 检索链路）。
 */
public enum Intent {
    /**
     * 简历/候选人信息检索相关问题。
     * <p>
     * 示例：项目经历、技能匹配、教育背景、工作年限等查询。
     */
    RESUME_QUERY,

    /**
     * 通用知识问答或任务型问答。
     * <p>
     * 示例：事实查询、方案说明、操作解释等。
     */
    GENERAL_QA,

    /**
     * 闲聊或寒暄类对话。
     * <p>
     * 示例：打招呼、情绪表达、无明确任务目标的聊天。
     */
    CHITCHAT,

    /**
     * 未知/无法判定意图。
     * <p>
     * 通常用于模型输出异常、语义过短或上下文不足的兜底场景。
     */
    UNKNOWN;

    /**
     * 将模型输出标签安全解析为 {@link Intent}。
     * <p>
     * 解析策略：
     * 1. 输入为空或空白，返回 {@link #UNKNOWN}；
     * 2. 按枚举名（忽略大小写）匹配；
     * 3. 解析失败时返回 {@link #UNKNOWN}，避免抛异常影响主流程。
     *
     * @param value 模型返回的意图标签文本
     * @return 对应意图；无法识别时返回 {@link #UNKNOWN}
     */
    public static Intent from(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        try {
            return Intent.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return UNKNOWN;
        }
    }
}
