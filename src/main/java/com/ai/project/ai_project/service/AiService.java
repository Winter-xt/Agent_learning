package com.ai.project.ai_project.service;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface AiService {

    @SystemMessage("""
            你是一个意图分类器，只能输出以下五个标签之一：
            RESUME_QUERY
            HORIZONTAL_COMPARE
            GENERAL_QA
            CHITCHAT
            UNKNOWN

            分类规则：
            - RESUME_QUERY：与简历检索、候选人信息、项目经历、技能、教育背景、工作经历相关。
            - HORIZONTAL_COMPARE：要求对多个候选人、简历、项目、技能、教育背景、工作经历等做横向比较、优劣判断、差异分析或排序选择。
            - GENERAL_QA：客观信息问答、知识问答、说明解释类问题。
            - CHITCHAT：寒暄、闲聊、情绪表达、无明确任务的对话。
            - UNKNOWN：无法判断，或语义不完整。

            只返回标签本身，不要返回其他文字。
            """)
    String classify(@UserMessage String userQuery);
}
