-- Share links (decision 2026-11-06): a short, unguessable code that opens a public page showing what the sharer chose,
-- a snapshot built from the owning modules' records (a receipt, a group statement, an invite, "pay me", an address).
-- Nothing from any chat. Never edit this script once applied; a change is a new script.

CREATE TABLE share.links (
    code       text        PRIMARY KEY,                 -- 12 base62 characters from a secure random source
    owner_id   uuid        NOT NULL,
    kind       text        NOT NULL,                    -- RECEIPT | STATEMENT | INVITE | PAY_ME | ADDRESS
    snapshot   text,                                    -- typed JSON of what the page shows; NULL once revoked
    created_at timestamptz NOT NULL,
    ends_at    timestamptz,                             -- when the sharer said it stops opening; NULL: never
    revoked_at timestamptz,
    expires_at timestamptz                              -- when the row goes: 30 days after it ends or is revoked
);
-- SH2: the owner's links, newest first (keyset by created_at, code); also account deletion.
CREATE INDEX links_owner_idx ON share.links (owner_id, created_at, code);
-- share.purge-expired.
CREATE INDEX links_expires_at_idx ON share.links (expires_at);
