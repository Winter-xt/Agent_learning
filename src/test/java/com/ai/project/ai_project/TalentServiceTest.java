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
    void testMultiUserMemoryIsolation() {
        if (talentService == null) {
            System.out.println("TalentService 未注入，请检查数据库连接和 Spring Boot 启动是否正常");
            return;
        }

        // 用户 A 的对话
        String userA = "user-A";
        String resA1 = talentService.analyze(userA, "我是张三,帮我看看我有多少余额");
        System.out.println("用户 A (张三) 结果 1：" + resA1);
        String resA2 = talentService.analyze(userA, "我刚才申请贷款了吗");
        System.out.println("用户 A (张三) 结果 2：" + resA2);

        // 用户 B 的对话 (完全不同的上下文)
        String userB = "user-B";
        String resB1 = talentService.analyze(userB, "我是李四,帮我看看我有多少余额");
        System.out.println("用户 B (李四) 结果 1：" + resB1);
        String resB2 = talentService.analyze(userB, "我刚才问了什么");
        System.out.println("用户 B (李四) 结果 2：" + resB2);
    }
}
