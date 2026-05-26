package com.ai.project.ai_project.config;

import com.ai.project.ai_project.service.ResumeMcpTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 工具注册配置。
 */
@Configuration
public class McpToolConfig {

    /**
     * 将简历 RAG 能力注册为 Spring AI Tool，并由 MCP Server starter 自动暴露给 MCP Client。
     *
     * @param resumeMcpTools 简历 RAG MCP 工具集合
     * @return Spring AI Tool 回调提供器
     */
    @Bean
    public ToolCallbackProvider resumeMcpToolCallbackProvider(ResumeMcpTools resumeMcpTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(resumeMcpTools)
                .build();
    }
}
