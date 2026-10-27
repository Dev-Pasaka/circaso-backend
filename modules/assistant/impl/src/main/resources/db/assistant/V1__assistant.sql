-- The assistant's tables (assistant.md → Data). Never edit this script once applied; a change is a new V2.

CREATE TABLE assistant.asha_messages (                  -- the Asha chat
    user_id     uuid        NOT NULL,
    message_id  uuid        NOT NULL,                   -- UUIDv7 made on the server; newest first by id
    role        text        NOT NULL,                   -- USER | ASHA
    body        text        NOT NULL,                   -- sealed (AES-GCM) before it is stored
    reply_to    uuid,
    answered_by uuid,
    asked_from  text,
    read_note   text,                                   -- JSON: the chat ids and the number of messages Asha read, never the messages
    created_at  timestamptz NOT NULL,
    PRIMARY KEY (user_id, message_id)                   -- pages by keyset, message_id DESC
);

CREATE TABLE assistant.usage_daily (                    -- daily totals for cost reports
    user_id       uuid        NOT NULL,
    usage_day     date        NOT NULL,
    text_turns    int         NOT NULL,
    voice_seconds int         NOT NULL,
    input_tokens  bigint      NOT NULL,
    output_tokens bigint      NOT NULL,
    updated_at    timestamptz NOT NULL,
    PRIMARY KEY (user_id, usage_day)
);

CREATE TABLE assistant.entitlements (
    user_id      uuid        PRIMARY KEY,
    plan         text        NOT NULL,                  -- FREE | PLUS
    plan_ends_at timestamptz                            -- when the paid plan ends; NULL for the free plan
);

CREATE TABLE assistant.audit (                          -- append-only; what happened, never what was said
    user_id    uuid NOT NULL,
    event_id   uuid NOT NULL,                           -- UUIDv7 made in the app
    kind       text NOT NULL,
    detail     text NOT NULL,
    request_id text,
    PRIMARY KEY (user_id, event_id)
);

-- The shared outbox shape (architecture.md → Durable events).
CREATE TABLE assistant.outbox (
    event_id     uuid        NOT NULL,
    occurred_at  timestamptz NOT NULL,
    type         text        NOT NULL,
    payload      text        NOT NULL,
    published_at timestamptz,
    PRIMARY KEY (event_id, occurred_at)
) PARTITION BY RANGE (occurred_at);                     -- daily partitions, dropped after 7 days
CREATE INDEX outbox_unpublished_idx ON assistant.outbox (occurred_at) WHERE published_at IS NULL;
