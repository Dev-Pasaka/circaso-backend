-- Staff's list of groups on the ops gateway (decision 2026-11-08): GroupReports.groups, every group newest first by
-- keyset on (created_at, chat_id), and groupCount, both on this partial index of groups only (direct chats, the bulk of
-- the table, aren't in it). Plain CREATE INDEX: Flyway runs each script in a transaction, and the table is small at
-- this stage. Never edit this script once applied; a change is a new one.
CREATE INDEX IF NOT EXISTS chats_groups_newest_idx ON messaging.chats (created_at DESC, chat_id DESC)
    WHERE kind = 'GROUP';
