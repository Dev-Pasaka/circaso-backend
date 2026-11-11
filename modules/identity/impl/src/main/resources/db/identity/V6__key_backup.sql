-- Group key backup (2026-11-12): each person's group meta keys, encrypted on the phone under a random backup root key,
-- and that root sealed under a key derived from the circaso.com passkey's WebAuthn PRF output (the PIN Vault's passkey).
-- The server keeps every blob as sent and never sees a key. Both tables are deleted with the account's other rows when
-- its deletion completes, and by KB3. Never edit this script once applied.
CREATE TABLE IF NOT EXISTS identity.key_backup_roots (
    user_id       uuid        PRIMARY KEY,
    credential_id text        NOT NULL,                   -- base64url WebAuthn credential id of the passkey
    salt          text        NOT NULL,                   -- base64, 16 to 64 bytes
    sealed_root   text        NOT NULL,                   -- base64, at most 1 KiB decoded
    algorithm     text        NOT NULL,                   -- e.g. prf-hkdf-sha256+aes-256-gcm/v1
    version       int         NOT NULL,                   -- 1, then one more on each replace
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL
);

CREATE TABLE IF NOT EXISTS identity.group_key_backups (
    user_id      uuid        NOT NULL,
    chat_id      uuid        NOT NULL,                    -- messaging's chat id (no foreign key: another schema)
    meta_version int         NOT NULL CHECK (meta_version >= 0),
    ciphertext   text        NOT NULL,                    -- base64, at most 512 bytes decoded
    updated_at   timestamptz NOT NULL,
    PRIMARY KEY (user_id, chat_id, meta_version)          -- also KB5's keyset order and the per-account count
);
