-- Group join links (decision 2026-11-14): an admin makes a link that anyone can join the group with, so a group can
-- move over from another chat app without the admin adding everyone. The server can't read a group's sealed name, so
-- the admin's phone publishes what the link's page shows, for that link only: name, description and a small photo.
-- Never members, messages or anything about money. Never edit this script once applied; a change is a new one.
CREATE TABLE IF NOT EXISTS messaging.group_links (
    token       text        PRIMARY KEY,                -- 22 base62 characters from a secure random source (131 bits)
    chat_id     uuid        NOT NULL REFERENCES messaging.chats ON DELETE CASCADE,
    created_by  uuid        NOT NULL,                   -- the admin who made it: the inviter of whoever joins with it
    name        text,                                   -- published by the admin's phone; NULL once reset or turned off
    description text,
    photo       text,                                   -- base64 of a small JPEG or PNG (at most 64 KB); NULL: none
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,                   -- moves when the preview is published again
    revoked_at  timestamptz,                            -- reset or turned off: the published copy is deleted with it
    expires_at  timestamptz                             -- when the tombstone goes: 30 days after revoked_at
);
-- One live link per group.
CREATE UNIQUE INDEX IF NOT EXISTS group_links_live_key ON messaging.group_links (chat_id) WHERE revoked_at IS NULL;
-- messaging.purge-expired.
CREATE INDEX IF NOT EXISTS group_links_expires_at_idx ON messaging.group_links (expires_at);

-- Who an admin removed from a group, so its join link can't bring them back (an admin adds them instead). A leave
-- writes no row; accepting an admin's invite deletes it.
CREATE TABLE IF NOT EXISTS messaging.group_removals (
    chat_id    uuid        NOT NULL REFERENCES messaging.chats ON DELETE CASCADE,
    user_id    uuid        NOT NULL,
    removed_by uuid        NOT NULL,
    removed_at timestamptz NOT NULL,
    PRIMARY KEY (chat_id, user_id)
);
