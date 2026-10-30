-- Encrypted chat attachments, AT1-AT3 (decision 2026-10-30): one row per file the phone uploads to object storage as
-- ciphertext. The AES key and digest travel only inside the E2EE message, never here. Rows are deleted by
-- `messaging.purge-attachments` (storage object first, then the row): 30 days after AT1, or a day after it while
-- still PENDING. No foreign key to chats: an attachment outlives nothing but its own 30 days.

CREATE TABLE messaging.attachments (
    attachment_id uuid        PRIMARY KEY,                -- UUIDv7, made on the server
    chat_id       uuid        NOT NULL,
    uploader_id   uuid        NOT NULL,
    kind          text        NOT NULL CHECK (kind IN ('IMAGE', 'VOICE', 'VIDEO', 'FILE')),
    size_bytes    bigint      NOT NULL CHECK (size_bytes > 0),
    state         text        NOT NULL CHECK (state IN ('PENDING', 'READY')),
    created_at    timestamptz NOT NULL,
    expires_at    timestamptz NOT NULL                    -- created_at + 30 days
);
CREATE INDEX attachments_expires_at_idx ON messaging.attachments (expires_at);
CREATE INDEX attachments_pending_created_at_idx ON messaging.attachments (created_at) WHERE state = 'PENDING';
