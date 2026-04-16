package com.ai.project.ai_project.tools;

import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

@Component
public class FinancialTools {

    @Tool("查询指定用户的银行账户余额")
    public double getAccountBalance(String userName) {
        System.out.println("--- 系统日志：AI 正在调用 Java 方法查询 [" + userName + "] 的余额 ---");
        if ("张三".equals(userName)) return 1000.50;
        if ("李四".equals(userName)) return 500.00;
        return 0.0;
    }

    @Tool("申请贷款")
    public double applyForLoan(String userName) {
        System.out.println("--- 系统日志：AI 正在调用 Java 方法申请贷款 ---");
        return 1000.0;
    }
}
