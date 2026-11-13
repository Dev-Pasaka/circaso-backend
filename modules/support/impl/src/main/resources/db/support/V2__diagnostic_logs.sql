-- Diagnostic logs (2026-11-13): a phone's recent technical log, sent by the person from You → Help → "Send diagnostic
-- log" so support can see why something failed (a dropped call, a stuck message). The phone scrubs it before sending
-- and the server again before storing: no message text, names, phone numbers, emails, tokens or keys. Deleted by
-- support.purge-expired once expires_at passes (30 days after it came), and with the account. Never edit this script
-- once applied; a change is a new script.

CREATE TABLE support.diagnostic_logs (
    log_id       uuid        PRIMARY KEY,                -- UUIDv7, made on the server
    code         text        NOT NULL,                   -- DL- and six characters, what the person reads out
    user_id      uuid        NOT NULL,
    platform     text        NOT NULL,                   -- android | ios
    app_version  text        NOT NULL,
    os_version   text        NOT NULL,
    device_model text        NOT NULL,
    size_bytes   integer     NOT NULL,                   -- the log's UTF-8 size, at most 2 MiB
    log          text        NOT NULL,
    created_at   timestamptz NOT NULL,
    expires_at   timestamptz NOT NULL,
    CONSTRAINT diagnostic_logs_code_key UNIQUE (code)
);
-- A person's logs for staff, newest first; also account deletion.
CREATE INDEX diagnostic_logs_user_idx ON support.diagnostic_logs (user_id, log_id);
-- support.purge-expired.
CREATE INDEX diagnostic_logs_expires_idx ON support.diagnostic_logs (expires_at);
