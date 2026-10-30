-- Chama's tables (decision 2026-10-29: a group's goal with a treasurer; no group-owned key). A goal per group chat,
-- the contributions counted towards it (one per confirmed transfer), the payment requests the admins decide and the
-- treasurer pays, and the realtime frames kept for replay. Never edit this script once applied; a change is a new
-- script.

CREATE TABLE chama.goals (
    chat_id       uuid        PRIMARY KEY,              -- messaging's chat id
    name          text        NOT NULL,
    target_minor  bigint      NOT NULL CHECK (target_minor > 0),
    asset         text        NOT NULL,                 -- USDC
    deadline      timestamptz,
    treasurer_id  uuid        NOT NULL,                 -- whose wallet holds the money
    created_by    uuid        NOT NULL,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    version       bigint      NOT NULL DEFAULT 0
);

CREATE TABLE chama.contributions (
    transfer_id   uuid        PRIMARY KEY,              -- the wallet transfer that paid it
    chat_id       uuid        NOT NULL,
    user_id       uuid        NOT NULL,
    amount_minor  bigint      NOT NULL CHECK (amount_minor > 0),
    asset         text        NOT NULL,
    at            timestamptz NOT NULL
);
CREATE INDEX contributions_chat_idx ON chama.contributions (chat_id, at DESC);

CREATE TABLE chama.requests (
    request_id       uuid        PRIMARY KEY,
    chat_id          uuid        NOT NULL,
    requested_by     uuid        NOT NULL,
    payee_id         uuid,                               -- a Circaso user, or
    payee_address    text,                               -- an outside address
    payee_name       text,
    amount_minor     bigint      NOT NULL CHECK (amount_minor > 0),
    asset            text        NOT NULL,
    note             text,
    state            text        NOT NULL,              -- PENDING | APPROVED | DECLINED | PAID | EXPIRED
    approvals_needed integer     NOT NULL,
    transfer_id      uuid,                               -- the treasurer's transfer, once linked
    expires_at       timestamptz NOT NULL,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL
);
CREATE INDEX requests_chat_idx ON chama.requests (chat_id, created_at DESC);
CREATE INDEX requests_open_idx ON chama.requests (state, expires_at) WHERE state = 'PENDING';
CREATE UNIQUE INDEX requests_transfer_key ON chama.requests (transfer_id) WHERE transfer_id IS NOT NULL;

CREATE TABLE chama.approvals (
    request_id  uuid        NOT NULL,
    user_id     uuid        NOT NULL,
    decision    text        NOT NULL,                   -- APPROVE | DECLINE
    at          timestamptz NOT NULL,
    PRIMARY KEY (request_id, user_id)
);

-- The frames chama sent, kept 7 days for replay (daily partitions made and dropped by chama.purge-expired).
CREATE TABLE chama.realtime (
    user_id  uuid        NOT NULL,
    seq      bigint      NOT NULL,
    frame    text        NOT NULL,
    sent_at  timestamptz NOT NULL,
    PRIMARY KEY (user_id, seq, sent_at)
) PARTITION BY RANGE (sent_at);

-- The shared outbox shape (architecture.md → Durable events).
CREATE TABLE chama.outbox (
    event_id     uuid        NOT NULL,
    occurred_at  timestamptz NOT NULL,
    type         text        NOT NULL,
    payload      text        NOT NULL,
    published_at timestamptz,
    PRIMARY KEY (event_id, occurred_at)
) PARTITION BY RANGE (occurred_at);
CREATE INDEX outbox_unpublished_idx ON chama.outbox (occurred_at) WHERE published_at IS NULL;
