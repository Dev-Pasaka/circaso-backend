-- Identity's first schema (identity.md → Data). Applied by Flyway in the Migrate step, as the role `identity`.
-- Never edit this file once it has been applied anywhere: a change is a new V2 script.

CREATE TABLE identity.accounts (
    user_id          uuid        PRIMARY KEY,
    phone            text,                                -- E.164, set once verified
    status           text        NOT NULL,                -- PENDING_PHONE | ACTIVE | RECOVERY_PENDING | SUSPENDED | BLOCKED | DELETED
    active_device_id uuid,
    verification     text        NOT NULL DEFAULT 'NONE', -- NONE | PENDING | VERIFIED (KYC)
    session_version  int         NOT NULL DEFAULT 1,      -- bump to invalidate every token at once
    delete_after     timestamptz,                         -- DELETED: when the grace period ends and deletion completes
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    version          bigint      NOT NULL DEFAULT 0       -- optimistic lock
);
-- One account per number: the unique index is the claim.
CREATE UNIQUE INDEX accounts_phone_key ON identity.accounts (phone);
-- The 30-day sweep of pending accounts.
CREATE INDEX accounts_pending_idx ON identity.accounts (created_at) WHERE status = 'PENDING_PHONE';
-- The job that completes deletion after the grace period.
CREATE INDEX accounts_deleted_idx ON identity.accounts (delete_after) WHERE status = 'DELETED';

CREATE TABLE identity.provider_links (
    user_id   uuid        NOT NULL,
    provider  text        NOT NULL,                       -- GOOGLE | APPLE
    subject   text        NOT NULL,
    email     text,
    linked_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, provider)
);
-- One account per Google/Apple login: the unique index is the claim.
CREATE UNIQUE INDEX provider_links_login_key ON identity.provider_links (provider, subject);

CREATE TABLE identity.devices (
    user_id       uuid        NOT NULL,
    device_id     uuid        NOT NULL,
    platform      text        NOT NULL,
    model         text        NOT NULL,
    app_version   text        NOT NULL,
    public_key    text        NOT NULL,
    key_algorithm text        NOT NULL,
    push_token    text,
    status        text        NOT NULL,                   -- ACTIVE | REPLACED | REMOVED
    registered_at timestamptz NOT NULL,
    last_seen_at  timestamptz NOT NULL,
    PRIMARY KEY (user_id, device_id)
);

CREATE TABLE identity.sessions (
    session_id         uuid        PRIMARY KEY,
    user_id            uuid        NOT NULL,
    device_id          uuid        NOT NULL,
    current_token_hash text        NOT NULL,              -- SHA-256 of the live refresh token
    scope              text        NOT NULL,
    status             text        NOT NULL,              -- ACTIVE | REVOKED
    created_at         timestamptz NOT NULL,
    rotated_at         timestamptz NOT NULL,
    expires_at         timestamptz NOT NULL               -- rotated_at + 30 days: filtered on every read, then purged
);
-- For identity.purge-expired.
CREATE INDEX sessions_expires_at_idx ON identity.sessions (expires_at);

CREATE TABLE identity.security_log (
    user_id      uuid NOT NULL,
    event_id     uuid NOT NULL,                           -- UUIDv7 made in the app; newest first by id
    type         text NOT NULL,                           -- SIGN_IN | NEW_PHONE | PHONE_VERIFIED | PROVIDER_LINKED | ...
    device_model text,
    detail       text,
    PRIMARY KEY (user_id, event_id)                       -- I13 pages it by keyset, event_id DESC
);

CREATE TABLE identity.support_recoveries (
    request_id     uuid        PRIMARY KEY,               -- agents open a case by request id
    user_id        uuid        NOT NULL,
    status         text        NOT NULL,                  -- RECEIVED | VERIFYING | NOTICE_SENT | RELINKED | REJECTED | CANCELLED
    contact_email  text        NOT NULL,
    note           text,
    id_check       text,
    selfie_match   text,
    agent_id       text,
    notice_sent_at timestamptz,
    relink_after   timestamptz,
    new_provider   text,
    new_subject    text
);
CREATE INDEX support_recoveries_user_idx ON identity.support_recoveries (user_id, request_id DESC);
-- The job that relinks 48 h after the notice.
CREATE INDEX support_recoveries_relink_idx ON identity.support_recoveries (relink_after) WHERE status = 'NOTICE_SENT';
-- /ops/recoveries: the cases in a status, oldest first.
CREATE INDEX support_recoveries_status_idx ON identity.support_recoveries (status, request_id);

-- The shared outbox (architecture.md → Durable events): infrastructure writes and relays it.
CREATE TABLE identity.outbox (
    event_id     uuid        NOT NULL,                    -- UUIDv7
    occurred_at  timestamptz NOT NULL,
    type         text        NOT NULL,
    payload      text        NOT NULL,                    -- the JSON envelope
    published_at timestamptz,
    PRIMARY KEY (event_id, occurred_at)
) PARTITION BY RANGE (occurred_at);                       -- daily partitions, dropped after 7 days
CREATE INDEX outbox_unpublished_idx ON identity.outbox (occurred_at) WHERE published_at IS NULL;
