-- Group invites (decision 2026-11-09): adding someone to a group invites them, and they join only when they accept.
-- One row per invite; a decided one keeps its row until it expires, so a retried accept or decline finds its answer.
-- Never edit this script once applied; a change is a new one.
CREATE TABLE IF NOT EXISTS messaging.invites (
    invite_id   uuid        PRIMARY KEY,
    chat_id     uuid        NOT NULL REFERENCES messaging.chats ON DELETE CASCADE,
    user_id     uuid        NOT NULL,                   -- the person invited
    invited_by  uuid        NOT NULL,
    state       text        NOT NULL,                   -- PENDING | ACCEPTED | DECLINED | CANCELLED
    sealed_key  text,                                   -- the group's metadata key, sealed 1:1 by the inviter's phone
    created_at  timestamptz NOT NULL,
    expires_at  timestamptz NOT NULL,                   -- 30 days after created_at; purged by messaging.purge-expired
    decided_at  timestamptz
);
-- One open invite per person per group: a second add while one waits keeps the first.
CREATE UNIQUE INDEX IF NOT EXISTS invites_pending_key ON messaging.invites (chat_id, user_id) WHERE state = 'PENDING';
-- The person's inbox of invites, newest first.
CREATE INDEX IF NOT EXISTS invites_user_idx ON messaging.invites (user_id, created_at) WHERE state = 'PENDING';
CREATE INDEX IF NOT EXISTS invites_expires_at_idx ON messaging.invites (expires_at);
