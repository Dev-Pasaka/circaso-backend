-- A merchant's FinoQ (PayHero) webhook signing secret (2026-11-23), entered on the portal. It is a secret, so it is
-- sealed, never plaintext: AES-256-GCM under a key derived from the ramp's master key alone (not the PIN), bound to
-- the merchant. Callbacks arrive while the merchant is offline, when nothing sealed under their PIN can be opened; the
-- secret only lets the server tell a forged callback from FinoQ's, and a callback is only a doorbell, so it moves no
-- money and needs no PIN. Both columns are set together and cleared together.
ALTER TABLE ramp.merchants
    ADD COLUMN webhook_secret_sealed     bytea,
    ADD COLUMN webhook_secret_updated_at timestamptz,
    ADD CONSTRAINT merchants_webhook_secret_check
        CHECK ((webhook_secret_sealed IS NULL) = (webhook_secret_updated_at IS NULL));
