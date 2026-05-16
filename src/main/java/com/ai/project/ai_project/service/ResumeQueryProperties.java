package com.ai.project.ai_project.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 简历查询链路可调参数。
 */
@Component
class ResumeQueryProperties {

    private final int scoreCliffMinContexts;
    private final int horizontalCompareScoreCliffMinContexts;
    private final double scoreCliffRetainRatio;
    private final double scoreCliffMinGap;
    private final int resumeVectorMaxResults;
    private final int horizontalCompareVectorMaxResults;
    private final int resumeKeywordMaxResults;
    private final int horizontalCompareKeywordMaxResults;
    private final int resumeParentContexts;
    private final int horizontalCompareParentContexts;

    ResumeQueryProperties(@Value("${app.resume.query.score-cliff-min-contexts:1}") int scoreCliffMinContexts,
                          @Value("${app.resume.query.horizontal-score-cliff-min-contexts:4}") int horizontalCompareScoreCliffMinContexts,
                          @Value("${app.resume.query.score-cliff-retain-ratio:0.45}") double scoreCliffRetainRatio,
                          @Value("${app.resume.query.score-cliff-min-gap:0.12}") double scoreCliffMinGap,
                          @Value("${app.resume.query.vector-max-results:50}") int resumeVectorMaxResults,
                          @Value("${app.resume.query.horizontal-vector-max-results:80}") int horizontalCompareVectorMaxResults,
                          @Value("${app.resume.query.keyword-max-results:12}") int resumeKeywordMaxResults,
                          @Value("${app.resume.query.horizontal-keyword-max-results:24}") int horizontalCompareKeywordMaxResults,
                          @Value("${app.resume.query.parent-contexts:4}") int resumeParentContexts,
                          @Value("${app.resume.query.horizontal-parent-contexts:8}") int horizontalCompareParentContexts) {
        this.scoreCliffMinContexts = scoreCliffMinContexts;
        this.horizontalCompareScoreCliffMinContexts = horizontalCompareScoreCliffMinContexts;
        this.scoreCliffRetainRatio = scoreCliffRetainRatio;
        this.scoreCliffMinGap = scoreCliffMinGap;
        this.resumeVectorMaxResults = resumeVectorMaxResults;
        this.horizontalCompareVectorMaxResults = horizontalCompareVectorMaxResults;
        this.resumeKeywordMaxResults = resumeKeywordMaxResults;
        this.horizontalCompareKeywordMaxResults = horizontalCompareKeywordMaxResults;
        this.resumeParentContexts = resumeParentContexts;
        this.horizontalCompareParentContexts = horizontalCompareParentContexts;
    }

    int scoreCliffMinContexts() {
        return scoreCliffMinContexts;
    }

    int horizontalCompareScoreCliffMinContexts() {
        return horizontalCompareScoreCliffMinContexts;
    }

    double scoreCliffRetainRatio() {
        return scoreCliffRetainRatio;
    }

    double scoreCliffMinGap() {
        return scoreCliffMinGap;
    }

    int resumeVectorMaxResults() {
        return resumeVectorMaxResults;
    }

    int horizontalCompareVectorMaxResults() {
        return horizontalCompareVectorMaxResults;
    }

    int resumeKeywordMaxResults() {
        return resumeKeywordMaxResults;
    }

    int horizontalCompareKeywordMaxResults() {
        return horizontalCompareKeywordMaxResults;
    }

    int resumeParentContexts() {
        return resumeParentContexts;
    }

    int horizontalCompareParentContexts() {
        return horizontalCompareParentContexts;
    }
}
