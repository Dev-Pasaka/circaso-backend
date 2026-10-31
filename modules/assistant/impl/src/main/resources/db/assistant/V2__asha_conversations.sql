-- Separate conversations with Asha (decision 2026-10-31): the user can start a brand new chat, and Asha reads only the
-- conversation she is in. Every message belongs to one conversation of its user.

CREATE TABLE IF NOT EXISTS assistant.asha_conversations (
    user_id         uuid        NOT NULL,
    conversation_id uuid        NOT NULL,                   -- UUIDv7
    title           text,                                   -- sealed (AES-GCM) like the messages; NULL until titled
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,                   -- the last change of any kind: a message, a rename
    last_message_at timestamptz NOT NULL,                   -- the last message, or the creation of an empty one
    PRIMARY KEY (user_id, conversation_id)
);

-- The conversation list, newest first, paged by keyset on (last_message_at, conversation_id) DESC; the A2 fallback
-- (the user's most recent conversation) is its first row.
CREATE INDEX IF NOT EXISTS asha_conversations_recent_idx
    ON assistant.asha_conversations (user_id, last_message_at, conversation_id);

ALTER TABLE assistant.asha_messages
    ADD COLUMN IF NOT EXISTS conversation_id uuid;

-- Every user who has talked to Asha gets "conversation 1", holding all their messages so far. The title is left NULL:
-- the bodies are sealed with the app's key, which this script doesn't have, so the app titles it from its first user
-- message (or "Earlier chat") the first time it reads it (assistant.md → Conversations).
INSERT INTO assistant.asha_conversations (user_id, conversation_id, title, created_at, updated_at, last_message_at)
SELECT user_id, uuidv7(), NULL, min(created_at), max(created_at), max(created_at)
FROM assistant.asha_messages
WHERE conversation_id IS NULL
GROUP BY user_id;

UPDATE assistant.asha_messages AS m
SET conversation_id = c.conversation_id
FROM assistant.asha_conversations AS c
WHERE m.conversation_id IS NULL
  AND c.user_id = m.user_id;

ALTER TABLE assistant.asha_messages
    ALTER COLUMN conversation_id SET NOT NULL;

-- A conversation's messages, newest first by id: its page, Asha's context and its oldest message (for the title).
CREATE INDEX IF NOT EXISTS asha_messages_conversation_idx
    ON assistant.asha_messages (user_id, conversation_id, message_id);
