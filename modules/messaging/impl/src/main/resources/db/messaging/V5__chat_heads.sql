-- Each chat's newest MESSAGE, kept as one small row per chat so the chat list (M6) and the chat views read it by key
-- instead of probing every daily partition of message_records for every chat (decision 2026-11-05, perf). Never edit
-- this script once applied; a change is a new one.
--
-- Unpartitioned and keyed by chat_id. No foreign key to chats on purpose: its check would take a KEY SHARE lock on
-- the chat row in every send, which waits on the FOR UPDATE that membership changes hold. deleteChat removes the row.
CREATE TABLE messaging.chat_heads (
    chat_id         uuid        PRIMARY KEY,
    last_message_id uuid        NOT NULL,               -- the newest MESSAGE record of the chat (UUIDv7)
    last_message_at timestamptz NOT NULL                -- its sent_at
);
-- messaging.purge-expired forgets the heads whose record's partition it dropped: a range scan of those rows only.
CREATE INDEX chat_heads_last_message_idx ON messaging.chat_heads (last_message_id);

-- The head moves in the send's own transaction, with the record, and without a round trip of its own: an AFTER
-- INSERT row trigger on message_records (it applies to every partition, those created later included). The upsert is
-- monotonic, so sends that commit out of order, or a replayed insert, never move a head backwards.
CREATE FUNCTION messaging.move_chat_head() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    INSERT INTO messaging.chat_heads AS head (chat_id, last_message_id, last_message_at)
    VALUES (NEW.chat_id, NEW.message_id, NEW.sent_at)
    ON CONFLICT (chat_id) DO UPDATE
        SET last_message_id = EXCLUDED.last_message_id,
            last_message_at = EXCLUDED.last_message_at
        WHERE head.last_message_id < EXCLUDED.last_message_id;
    RETURN NULL;
END
$$;

CREATE TRIGGER message_records_chat_head
    AFTER INSERT ON messaging.message_records
    FOR EACH ROW
    WHEN (NEW.kind = 'MESSAGE')
EXECUTE FUNCTION messaging.move_chat_head();

-- Backfill from the records still held. The trigger exists first and the backfill is the same monotonic upsert, so a
-- send that lands while this runs is kept either way.
INSERT INTO messaging.chat_heads AS head (chat_id, last_message_id, last_message_at)
SELECT DISTINCT ON (chat_id) chat_id, message_id, sent_at
FROM messaging.message_records
WHERE kind = 'MESSAGE'
ORDER BY chat_id, message_id DESC
ON CONFLICT (chat_id) DO UPDATE
    SET last_message_id = EXCLUDED.last_message_id,
        last_message_at = EXCLUDED.last_message_at
    WHERE head.last_message_id < EXCLUDED.last_message_id;
