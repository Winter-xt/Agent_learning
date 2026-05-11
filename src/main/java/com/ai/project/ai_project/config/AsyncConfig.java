package com.ai.project.ai_project.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean
    public Executor aiTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 1. 针对 I/O 密集型，适当增加线程
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(20);
        // 2. 队列不宜过长，保证反馈及时性
        executor.setQueueCapacity(30);
        executor.setThreadNamePrefix("ai-engine-");
        // 3. 核心生产环境必加：让调用者执行，防止任务直接丢失并起到限流作用
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        return executor;
    }
}
