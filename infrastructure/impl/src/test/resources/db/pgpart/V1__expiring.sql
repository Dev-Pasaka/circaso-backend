-- Partitioned by time, like every module's outbox and realtime.
CREATE TABLE pgpart.events (
    event_id    uuid        NOT NULL,
    occurred_at timestamptz NOT NULL,
    PRIMARY KEY (event_id, occurred_at)
) PARTITION BY RANGE (occurred_at);

-- Partitioned by a UUIDv7 id, like messaging's inbox, group messages and message records.
CREATE TABLE pgpart.messages (
    message_id uuid PRIMARY KEY
) PARTITION BY RANGE (message_id);

-- Expired by expires_at and purged.
CREATE TABLE pgpart.sessions (
    session_id uuid        PRIMARY KEY,
    expires_at timestamptz NOT NULL
);
CREATE INDEX sessions_expires_at_idx ON pgpart.sessions (expires_at);
