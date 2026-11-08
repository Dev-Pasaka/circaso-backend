-- The staff side of Circaso (decision 2026-11-08): who may sign in to the admin, with which role, and the audit log of
-- every staff action. Staff are not app users: they sign in to the ops gateway through Cloudflare Access, which proves
-- the email; this table maps the email to a role. Never edit this script once applied; a change is a new script.

CREATE TABLE ops.staff (
    staff_id   uuid        PRIMARY KEY,                 -- UUIDv7
    email      text        NOT NULL,                    -- lowercased; the Access identity
    role       text        NOT NULL CHECK (role IN ('ADMIN', 'SUPPORT', 'FINANCE')),
    active     boolean     NOT NULL DEFAULT true,       -- false: every call refused from the next request on
    added_by   text,                                    -- the email of the admin who added them
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version    bigint      NOT NULL DEFAULT 0           -- optimistic lock for role and access changes
);
-- One row per email; also the sign-in lookup on every staff request.
CREATE UNIQUE INDEX staff_email_key ON ops.staff (email);

CREATE TABLE ops.audit_log (
    entry_id    uuid        PRIMARY KEY,                -- UUIDv7: newest first by id, the audit page's keyset
    actor_id    uuid        NOT NULL,                   -- the staff id (a bootstrap admin's is derived from the email)
    actor_email text        NOT NULL,                   -- as it was at the time
    actor_role  text        NOT NULL,
    action      text        NOT NULL,                   -- user.suspend | ticket.reply | staff.add | …
    target_type text        NOT NULL,                   -- user | ticket | staff | support_account
    target_id   text,
    reason      text,                                   -- required for suspend and unsuspend
    detail      text,                                   -- a short note of what changed; never a message body
    outcome     text        NOT NULL CHECK (outcome IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    error_code  text,
    request_id  text,                                   -- the request's id, to find its log lines
    at          timestamptz NOT NULL,
    finished_at timestamptz
);
-- The audit page's filters, each newest first by id.
CREATE INDEX audit_log_actor_idx ON ops.audit_log (actor_id, entry_id DESC);
CREATE INDEX audit_log_action_idx ON ops.audit_log (action, entry_id DESC);
-- A user's (or ticket's) history, and the latest suspension shown on the user page.
CREATE INDEX audit_log_target_idx ON ops.audit_log (target_type, target_id, entry_id DESC);
