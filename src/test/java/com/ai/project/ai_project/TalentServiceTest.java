package com.ai.project.ai_project;

import com.ai.project.ai_project.service.TalentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class TalentServiceTest {

    @Autowired(required = false)
    private TalentService talentService;

    @Test
    void testAnalyzeWithFinancialRag() {
        if (talentService == null) {
            System.out.println("TalentService 未注入，请检查数据库连接和 Spring Boot 启动是否正常");
            return;
        }
        String result = talentService.analyze("我是张三,帮我看看我有多少余额");
        System.out.println("AI 分析结果：" + result);
    }
}
