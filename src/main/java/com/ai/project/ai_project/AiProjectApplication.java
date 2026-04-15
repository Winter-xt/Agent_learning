package com.ai.project.ai_project;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.ai.project.ai_project.mapper")
public class AiProjectApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiProjectApplication.class, args);
    }

}
