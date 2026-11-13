-- Payment holds (wallet.md → Data; built 2026-11-11 for the PIN Vault). While a row's expires_at is ahead, W4 and W17
-- refuse to send from the person's wallet (wallet.on_hold) and W1 shows until when. One row per person: a newer hold
-- replaces an older one only when it lasts longer (GREATEST), so a short hold never cuts a long one short. Reasons:
-- VAULT_OPENED (identity.PinVaultOpened, 24 h) and, once support restores are built, RELINKED (48 h). Rows past
-- expires_at are hidden by every read and deleted by wallet.purge-expired. Never edit this script once applied.
CREATE TABLE IF NOT EXISTS wallet.payment_holds (
    user_id    uuid        PRIMARY KEY,
    reason     text        NOT NULL,                    -- VAULT_OPENED | RELINKED
    expires_at timestamptz NOT NULL                     -- the end of the hold
);
-- wallet.purge-expired.
CREATE INDEX IF NOT EXISTS payment_holds_expires_idx ON wallet.payment_holds (expires_at);
