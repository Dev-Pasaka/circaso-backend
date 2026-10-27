-- Users' tables (users.md → Data). Never edit this script once applied; a change is a new V2.

CREATE TABLE users.profiles (
    user_id           uuid        PRIMARY KEY,
    display_name      text        NOT NULL,
    about             text,
    handle            text,                             -- lowercase; the live handle, a copy of the owner's row in handles
    handle_changed_at timestamptz,
    avatar_key        text,
    privacy           text        NOT NULL,             -- JSON: avatar, about, handleLookup
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    version           bigint      NOT NULL DEFAULT 0    -- optimistic lock (U2's version)
);
CREATE UNIQUE INDEX profiles_handle_key ON users.profiles (handle);

CREATE TABLE users.handles (
    handle      text        PRIMARY KEY,                -- lowercase; one owner per handle, live or on hold
    user_id     uuid        NOT NULL,
    claimed_at  timestamptz NOT NULL,
    released_at timestamptz,                            -- set on a change or on account deletion
    expires_at  timestamptz                             -- released_at + 30 days: the hold for the old owner; NULL while in use
);
CREATE INDEX handles_user_idx ON users.handles (user_id);
CREATE INDEX handles_expires_at_idx ON users.handles (expires_at) WHERE expires_at IS NOT NULL;

CREATE TABLE users.contacts (
    owner_id   uuid        NOT NULL,
    contact_id uuid        NOT NULL,
    source     text        NOT NULL,                    -- PHONEBOOK | QR | INVITE | HANDLE
    status     text        NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE | REMOVED; removed rows stop sync from re-adding
    removed_at timestamptz,
    added_at   timestamptz NOT NULL,
    PRIMARY KEY (owner_id, contact_id)
);
CREATE INDEX contacts_target_idx ON users.contacts (contact_id) WHERE status = 'ACTIVE';

CREATE TABLE users.waiting_contacts (
    phone_hmac text        NOT NULL,                    -- HMAC-SHA256(phone, phone-HMAC key shared with identity)
    owner_id   uuid        NOT NULL,
    added_at   timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,                    -- added_at + 180 days
    PRIMARY KEY (phone_hmac, owner_id)
);
CREATE INDEX waiting_contacts_expires_at_idx ON users.waiting_contacts (expires_at);

CREATE TABLE users.blocks (
    user_id    uuid        NOT NULL,
    blocked_id uuid        NOT NULL,
    blocked_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, blocked_id)
);
CREATE INDEX blocks_blocked_idx ON users.blocks (blocked_id);

CREATE TABLE users.invites (
    token      text        PRIMARY KEY,
    owner_id   uuid        NOT NULL,
    kind       text        NOT NULL,                    -- LINK | QR
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL                     -- created_at + 7 days
);
CREATE INDEX invites_expires_at_idx ON users.invites (expires_at);

CREATE TABLE users.realtime (
    user_id uuid        NOT NULL,
    seq     bigint      NOT NULL,
    frame   text        NOT NULL,                       -- the envelope as JSON
    sent_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, seq, sent_at)
) PARTITION BY RANGE (sent_at);                         -- daily partitions, dropped after 7 days

-- The shared outbox shape (architecture.md → Durable events).
CREATE TABLE users.outbox (
    event_id     uuid        NOT NULL,
    occurred_at  timestamptz NOT NULL,
    type         text        NOT NULL,
    payload      text        NOT NULL,
    published_at timestamptz,
    PRIMARY KEY (event_id, occurred_at)
) PARTITION BY RANGE (occurred_at);                     -- daily partitions, dropped after 7 days
CREATE INDEX outbox_unpublished_idx ON users.outbox (occurred_at) WHERE published_at IS NULL;
