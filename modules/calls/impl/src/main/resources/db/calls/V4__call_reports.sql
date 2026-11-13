-- Why a phone left a call and what it recorded about it (2026-11-13). Three calls dropped between an Android phone and
-- an iPhone with nothing to say why, so phones now say why they left (CL7 `cause`) and send a report after each call
-- (CL10). Staff read both in the admin portal. Never edit this script once applied; a change is a new script.

-- The cause the leaver's phone gave on CL7 (USER_HANGUP, STALL, RECONNECT_GAVE_UP, ...; OTHER for one we don't know).
-- NULL for older apps, for people who never left by themselves, and again after a rejoin.
ALTER TABLE calls.participants
    ADD COLUMN IF NOT EXISTS left_cause text;

-- A person's calls for staff, newest first.
CREATE INDEX IF NOT EXISTS participants_user_idx ON calls.participants (user_id, call_id DESC);

-- One report per person per call, replaced by a repeat. The timeline, the samples and the summary are the phone's JSON,
-- stored as sent: no message content, phone numbers or names. Deleted by calls.purge-expired once expires_at passes
-- (30 days after it came).
CREATE TABLE calls.reports (
    call_id       uuid        NOT NULL,
    user_id       uuid        NOT NULL,
    platform      text        NOT NULL,                -- android | ios
    app_version   text        NOT NULL,
    os_version    text        NOT NULL,
    device_model  text        NOT NULL,
    video         boolean     NOT NULL,
    outgoing      boolean     NOT NULL,                -- this phone started the call
    end_cause     text        NOT NULL,
    started_at    timestamptz NOT NULL,                -- when this phone's timeline began
    ended_at      timestamptz NOT NULL,
    received_at   timestamptz NOT NULL,
    expires_at    timestamptz NOT NULL,
    summary       text        NOT NULL,
    events        text        NOT NULL,
    samples       text        NOT NULL,
    PRIMARY KEY (call_id, user_id)
);
CREATE INDEX reports_received_idx ON calls.reports (received_at);
CREATE INDEX reports_expires_idx ON calls.reports (expires_at);
