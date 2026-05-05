CREATE TABLE IF NOT EXISTS resume_document (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(255) NOT NULL,
    user_id_key VARCHAR(512) NOT NULL,
    candidate_name VARCHAR(255) NOT NULL DEFAULT '',
    original_file_name VARCHAR(512) NOT NULL DEFAULT '',
    stored_file_path VARCHAR(1024) NOT NULL DEFAULT '',
    content_type VARCHAR(255) NOT NULL DEFAULT '',
    file_size BIGINT NOT NULL DEFAULT 0,
    source_type VARCHAR(64) NOT NULL DEFAULT 'resume',
    segment_count INT NOT NULL DEFAULT 0,
    character_count INT NOT NULL DEFAULT 0,
    uploaded_at DATETIME NOT NULL,
    INDEX idx_resume_document_user_source (user_id_key, source_type),
    INDEX idx_resume_document_candidate (candidate_name)
);

ALTER TABLE resume_parent_block
    ADD COLUMN resume_document_id BIGINT NULL AFTER source_type;

CREATE INDEX idx_resume_parent_document ON resume_parent_block (resume_document_id);
