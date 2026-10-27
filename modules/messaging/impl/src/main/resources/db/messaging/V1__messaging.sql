-- Messaging's tables (messaging.md → Data). Never edit this script once applied; a change is a new V2.
-- The media tables come with MS5 (M15-M17), the history tables with MS6 (M18-M21).

CREATE TABLE messaging.chats (
    chat_id         uuid        PRIMARY KEY,
    kind            text        NOT NULL CHECK (kind IN ('DIRECT', 'GROUP')),
    direct_low      uuid,                               -- DIRECT: the smaller user id of the pair; NULL for groups
    direct_high     uuid,                               -- DIRECT: the larger user id of the pair; NULL for groups
    sealed_meta     text,                               -- groups: name and avatar under the group metadata key
    meta_version    int         NOT NULL DEFAULT 0,
    avatar_media_id uuid,                               -- the encrypted avatar file, deleted when replaced
    settings        text        NOT NULL,               -- JSON: membersCanAdd
    member_count    int         NOT NULL,               -- changed in the same transaction as the member rows
    owner_id        uuid,                               -- groups: the owner, the authority on who owns it
    created_by      uuid        NOT NULL,
    created_at      timestamptz NOT NULL,
    version         bigint      NOT NULL DEFAULT 0,     -- optimistic lock (M8); every membership change moves it
    CHECK ((kind = 'DIRECT') = (direct_low IS NOT NULL AND direct_high IS NOT NULL)),
    CHECK (direct_low < direct_high)
);
-- One direct chat per pair: M4 claims it with INSERT … ON CONFLICT DO NOTHING.
CREATE UNIQUE INDEX chats_direct_pair_key ON messaging.chats (direct_low, direct_high) WHERE kind = 'DIRECT';

CREATE TABLE messaging.members (
    chat_id    uuid        NOT NULL REFERENCES messaging.chats ON DELETE CASCADE,
    user_id    uuid        NOT NULL,
    role       text        NOT NULL,                    -- OWNER | ADMIN | MEMBER
    read_up_to uuid,                                    -- this member's read marker (messaging.read)
    joined_at  timestamptz NOT NULL,
    PRIMARY KEY (chat_id, user_id)
);
CREATE INDEX members_user_idx ON messaging.members (user_id, chat_id);   -- the chat list (M6)

CREATE TABLE messaging.key_bundles (
    user_id                  uuid        PRIMARY KEY,   -- one phone per account, so one bundle
    device_id                uuid        NOT NULL,
    identity_key             text        NOT NULL,
    signed_pre_key_id        int         NOT NULL,
    signed_pre_key           text        NOT NULL,
    signed_pre_key_signature text        NOT NULL,
    signed_pre_key_at        timestamptz NOT NULL,
    uploaded_at              timestamptz NOT NULL
);

CREATE TABLE messaging.one_time_prekeys (
    user_id     uuid        NOT NULL,
    key_id      int         NOT NULL,
    device_id   uuid        NOT NULL,                   -- the phone that uploaded it
    public_key  text        NOT NULL,
    uploaded_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, key_id)                       -- a resent key id is messaging.prekey_id_exists
);

-- The three message tables are partitioned by their UUIDv7 message_id, each day's bounds at the UUIDv7 value of
-- midnight UTC, so every lookup by id prunes to one partition. Migrate and messaging.purge-expired create the
-- partitions a day ahead and the job drops each one 30 days after it ends.

CREATE TABLE messaging.inbox (
    user_id    uuid        NOT NULL,                    -- the recipient
    message_id uuid        NOT NULL,                    -- OrderedIds; read oldest first
    chat_id    uuid        NOT NULL,
    sender_id  uuid        NOT NULL,
    kind       text        NOT NULL,                    -- MESSAGE | SENDER_KEY | CONTROL
    ciphertext text,                                    -- direct and SENDER_KEY: this recipient's own copy;
                                                        -- NULL for a group MESSAGE, whose body is in group_messages
    media_ids  uuid[]      NOT NULL DEFAULT '{}',
    sent_at    timestamptz NOT NULL,
    PRIMARY KEY (user_id, message_id)                   -- M13 pages WHERE user_id = ? AND message_id > ?
) PARTITION BY RANGE (message_id);

CREATE TABLE messaging.group_messages (                 -- one ciphertext per group message, not one per member
    chat_id    uuid        NOT NULL,
    message_id uuid        NOT NULL,
    sender_id  uuid        NOT NULL,
    ciphertext text        NOT NULL,
    sent_at    timestamptz NOT NULL,
    PRIMARY KEY (chat_id, message_id)
) PARTITION BY RANGE (message_id);

CREATE TABLE messaging.message_records (                -- who sent what, for M14 and read receipts after delivery
    message_id uuid        NOT NULL,
    chat_id    uuid        NOT NULL,
    sender_id  uuid        NOT NULL,
    kind       text        NOT NULL,
    sent_at    timestamptz NOT NULL,
    PRIMARY KEY (message_id)
) PARTITION BY RANGE (message_id);
-- M6: the newest message per chat (LATERAL … LIMIT 1).
CREATE INDEX message_records_chat_idx ON messaging.message_records (chat_id, message_id DESC);

CREATE TABLE messaging.client_messages (                -- idempotent send
    sender_id         uuid        NOT NULL,
    client_message_id uuid        NOT NULL,
    message_id        uuid        NOT NULL,
    expires_at        timestamptz NOT NULL,             -- 1 day after the send
    PRIMARY KEY (sender_id, client_message_id)
);
CREATE INDEX client_messages_expires_at_idx ON messaging.client_messages (expires_at);

CREATE TABLE messaging.settings (
    user_id       uuid    PRIMARY KEY,
    read_receipts boolean NOT NULL DEFAULT true,
    typing        boolean NOT NULL DEFAULT true
);

CREATE TABLE messaging.realtime (
    user_id uuid        NOT NULL,
    seq     bigint      NOT NULL,
    frame   text        NOT NULL,                       -- the envelope as JSON
    sent_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, seq, sent_at)
) PARTITION BY RANGE (sent_at);                         -- daily partitions, dropped after 7 days

-- The shared outbox shape (architecture.md → Durable events).
CREATE TABLE messaging.outbox (
    event_id     uuid        NOT NULL,
    occurred_at  timestamptz NOT NULL,
    type         text        NOT NULL,
    payload      text        NOT NULL,
    published_at timestamptz,
    PRIMARY KEY (event_id, occurred_at)
) PARTITION BY RANGE (occurred_at);                     -- daily partitions, dropped after 7 days
CREATE INDEX outbox_unpublished_idx ON messaging.outbox (occurred_at) WHERE published_at IS NULL;
