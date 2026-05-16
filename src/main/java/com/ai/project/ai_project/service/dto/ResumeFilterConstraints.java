package com.ai.project.ai_project.service.dto;

import java.util.List;

/**
 * 简历检索使用的已清洗 metadata 约束。
 *
 * @param parentType 偏好的父块类型，仅允许 project/resume 或空字符串
 * @param fileName 可选的原始文件名约束
 * @param contentType 可选的 MIME 类型约束
 * @param skills 技能和技术栈关键词
 * @param companies 公司或组织关键词，可包含“大厂”等泛化标签
 * @param schools 学校或教育背景关键词，可包含“名校”等泛化标签
 * @param titles 岗位、角色或职级关键词
 * @param projects 项目、产品或系统关键词
 * @param industries 行业或业务领域关键词
 * @param keywords 其他补充检索关键词
 */
public record ResumeFilterConstraints(
        String parentType,
        String fileName,
        String contentType,
        List<String> skills,
        List<String> companies,
        List<String> schools,
        List<String> titles,
        List<String> projects,
        List<String> industries,
        List<String> keywords
) {
    public static ResumeFilterConstraints empty() {
        return new ResumeFilterConstraints(
                "",
                "",
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }
}
