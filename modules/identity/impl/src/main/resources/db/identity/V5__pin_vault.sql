-- The PIN Vault (2026-11-11): one encrypted backup of the Circle wallet PIN (and, optionally, the security-question
-- answers) per account. The phone encrypts it under a key derived from a passkey's WebAuthn PRF output; the server keeps
-- the blob as sent and never sees the PIN or the key. Deleted with the account's other rows when its deletion completes.
-- Never edit this script once applied.
CREATE TABLE IF NOT EXISTS identity.pin_vaults (
    user_id       uuid        PRIMARY KEY,
    credential_id text        NOT NULL,                   -- base64url WebAuthn credential id of the passkey
    salt          text        NOT NULL,                   -- base64, 16 to 64 bytes
    ciphertext    text        NOT NULL,                   -- base64, at most 8 KiB decoded
    algorithm     text        NOT NULL,                   -- e.g. prf-hkdf-sha256+aes-256-gcm/v1
    version       int         NOT NULL,                   -- 1, then one more on each replace
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL
);
