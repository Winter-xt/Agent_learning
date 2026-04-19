-- Run this once on MySQL before deploying category-based chat memory storage.
-- It upgrades chat_memory from (id, messages) to (id, category, messages).

ALTER TABLE chat_memory
    ADD COLUMN category VARCHAR(64) NOT NULL DEFAULT 'default' AFTER id;

-- Rebuild primary key to allow the same id under different module categories.
ALTER TABLE chat_memory
    DROP PRIMARY KEY,
    ADD PRIMARY KEY (id, category);

CREATE INDEX idx_chat_memory_category ON chat_memory (category);
