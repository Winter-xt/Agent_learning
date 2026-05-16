package com.ai.project.ai_project.service;

import com.ai.project.ai_project.domain.ResumeQueryTraceEntity;
import com.ai.project.ai_project.mapper.ResumeQueryTraceMapper;
import com.ai.project.ai_project.service.dto.ResumeQueryTrace;
import com.ai.project.ai_project.util.ResumeTextUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class ResumeQueryTraceService {
    private static final Logger log = LoggerFactory.getLogger(ResumeQueryTraceService.class);

    private static final int ANSWER_PREVIEW_LIMIT = 1200;

    private final ResumeQueryTraceMapper resumeQueryTraceMapper;
    private final ObjectMapper objectMapper;

    public ResumeQueryTraceService(ResumeQueryTraceMapper resumeQueryTraceMapper, ObjectMapper objectMapper) {
        this.resumeQueryTraceMapper = resumeQueryTraceMapper;
        this.objectMapper = objectMapper;
    }

    @Async
    public void saveAsync(String userId,
                          String userIdKey,
                          String originalQuery,
                          String rewrittenQuery,
                          String intent,
                          String answer,
                          ResumeQueryTrace trace) {
        try {
            ResumeQueryTraceEntity entity = new ResumeQueryTraceEntity();
            entity.setTraceId(trace.traceId());
            entity.setUserId(userId);
            entity.setUserIdKey(userIdKey);
            entity.setOriginalQuery(ResumeTextUtils.safe(originalQuery));
            entity.setRewrittenQuery(ResumeTextUtils.safe(rewrittenQuery));
            entity.setIntent(ResumeTextUtils.safe(intent));
            entity.setAnswerPreview(limit(ResumeTextUtils.safe(answer), ANSWER_PREVIEW_LIMIT));
            entity.setTraceJson(toJson(trace));
            entity.setCreatedAt(LocalDateTime.now());
            resumeQueryTraceMapper.insert(entity);
        } catch (Exception e) {
            log.warn("保存简历查询 trace 失败，traceId={}", trace == null ? "" : trace.traceId(), e);
        }
    }

    private String toJson(ResumeQueryTrace trace) throws JsonProcessingException {
        return objectMapper.writeValueAsString(trace);
    }

    private String limit(String value, int limit) {
        if (value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit);
    }
}
