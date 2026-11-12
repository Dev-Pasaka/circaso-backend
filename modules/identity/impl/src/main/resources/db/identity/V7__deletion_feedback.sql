-- Deletion feedback (2026-11-12): why people asked for their account to go, from the website's deletion page (I17).
-- Deliberately carries no user id, so it outlives the account's deletion and points at no one. Never edit this script
-- once applied.
CREATE TABLE IF NOT EXISTS identity.deletion_feedback (
    feedback_id uuid        PRIMARY KEY,               -- UUIDv7, so rows sort by time
    reason      text        NOT NULL,                  -- not_useful | privacy | too_many_apps | switching | problems | other
    details     text,                                  -- optional free text, at most 500 characters
    source      text        NOT NULL,                  -- web
    created_at  timestamptz NOT NULL
);
