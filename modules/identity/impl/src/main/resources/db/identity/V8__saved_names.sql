-- Saved names (2026-11-12): the names a person gives their contacts ("Mum", "John Plumber"), backed up from the phone
-- as ONE blob sealed (AES-256-GCM) under the key backup root, which the server never has. The server keeps the bytes as
-- sent and compares versions; it can never read a name. Deleted by KB3 (turning key backup off) and with the account's
-- other rows when its deletion completes. Never edit this script once applied.
CREATE TABLE IF NOT EXISTS identity.saved_names (
    user_id    uuid        PRIMARY KEY,
    ciphertext text        NOT NULL,                   -- base64, at most 512 KiB decoded
    version    bigint      NOT NULL CHECK (version >= 1), -- 1, then one more on each save (compare-and-set)
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
