package com.ai.project.ai_project.test.tools;

import dev.langchain4j.agent.tool.Tool;

public class FinancialTools {

    @Tool("查询指定用户的银行账户余额")
    public double getAccountBalance(String userName) {
        System.out.println("--- 系统日志：AI 正在调用 Java 方法查询 [" + userName + "] 的余额 ---");
        // 这里你可以写真正的 JDBC 或 API 调用
        if ("张三".equals(userName)) return 10000.50;
        if ("李四".equals(userName)) return 500.00;
        return 0.0;
    }
}