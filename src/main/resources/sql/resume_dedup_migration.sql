ALTER TABLE resume_document
    ADD COLUMN candidate_name_key VARCHAR(255) NOT NULL DEFAULT '' AFTER candidate_name;

UPDATE resume_document
SET candidate_name_key = LOWER(REPLACE(REPLACE(REPLACE(candidate_name, ' ', ''), CHAR(9), ''), CHAR(10), ''))
WHERE candidate_name_key = '';

CREATE INDEX idx_resume_document_candidate_key ON resume_document (user_id_key(191), source_type, candidate_name_key(128));
