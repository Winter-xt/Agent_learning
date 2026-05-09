CREATE TABLE IF NOT EXISTS resume_query_trace (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    trace_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    user_id_key VARCHAR(512) NOT NULL,
    original_query TEXT NOT NULL,
    rewritten_query TEXT NOT NULL,
    intent VARCHAR(64) NOT NULL,
    trace_json JSON NOT NULL,
    answer_preview TEXT NULL,
    created_at DATETIME NOT NULL,
    KEY idx_resume_query_trace_trace_id (trace_id),
    KEY idx_resume_query_trace_user_created (user_id_key, created_at)
);
