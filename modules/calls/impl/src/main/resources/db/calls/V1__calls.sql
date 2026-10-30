-- Calls' tables (decision 2026-10-29: Cloudflare Realtime SFU, the server brokers SDP). A call per chat at a time,
-- everyone it rang or who joined, the tracks each joined participant publishes, the realtime frames kept for replay,
-- and the outbox. Never edit this script once applied; a change is a new script.

CREATE TABLE calls.calls (
    call_id      uuid        PRIMARY KEY,
    chat_id      uuid        NOT NULL,                 -- messaging's chat id
    kind         text        NOT NULL,                 -- VOICE | VIDEO
    state        text        NOT NULL,                 -- RINGING | ACTIVE | ENDED
    started_by   uuid        NOT NULL,
    is_group     boolean     NOT NULL,
    created_at   timestamptz NOT NULL,
    started_at   timestamptz,                          -- when a second person joined
    ended_at     timestamptz,
    end_reason   text,                                 -- COMPLETED | CANCELLED | MISSED | DECLINED
    active_at    timestamptz NOT NULL                  -- the last join, leave, pull or media change
);
-- At most one call that hasn't ended per chat: the claim behind calls.already_in_call.
CREATE UNIQUE INDEX calls_live_chat_key ON calls.calls (chat_id) WHERE state <> 'ENDED';
-- The ring timeout and the idle sweep read only calls that haven't ended.
CREATE INDEX calls_live_created_idx ON calls.calls (created_at) WHERE state <> 'ENDED';
CREATE INDEX calls_active_idx ON calls.calls (active_at) WHERE state = 'ACTIVE';

CREATE TABLE calls.participants (
    call_id     uuid        NOT NULL,
    user_id     uuid        NOT NULL,
    state       text        NOT NULL,                  -- INVITED | JOINED | DECLINED | MISSED | LEFT
    session_id  text,                                  -- the participant's SFU session while JOINED
    audio       boolean     NOT NULL,
    video       boolean     NOT NULL,
    joined_at   timestamptz,
    updated_at  timestamptz NOT NULL,
    PRIMARY KEY (call_id, user_id)
);

CREATE TABLE calls.tracks (
    call_id     uuid NOT NULL,
    user_id     uuid NOT NULL,
    track_name  text NOT NULL,
    kind        text NOT NULL,                         -- audio | video
    mid         text NOT NULL,                         -- the transceiver it was pushed on
    PRIMARY KEY (call_id, user_id, track_name)
);

-- The frames calls sent, kept 7 days for replay (daily partitions made and dropped by calls.purge-expired).
CREATE TABLE calls.realtime (
    user_id  uuid        NOT NULL,
    seq      bigint      NOT NULL,
    frame    text        NOT NULL,
    sent_at  timestamptz NOT NULL,
    PRIMARY KEY (user_id, seq, sent_at)
) PARTITION BY RANGE (sent_at);

-- The shared outbox shape (architecture.md → Durable events).
CREATE TABLE calls.outbox (
    event_id     uuid        NOT NULL,
    occurred_at  timestamptz NOT NULL,
    type         text        NOT NULL,
    payload      text        NOT NULL,
    published_at timestamptz,
    PRIMARY KEY (event_id, occurred_at)
) PARTITION BY RANGE (occurred_at);
CREATE INDEX outbox_unpublished_idx ON calls.outbox (occurred_at) WHERE published_at IS NULL;
