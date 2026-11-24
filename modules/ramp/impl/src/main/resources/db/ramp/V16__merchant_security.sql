-- Merchant security (2026-11-24, the owner's request): withdrawals only to approved addresses, email alerts for every
-- security event and for activity, a "This wasn't me" link that locks withdrawals, and passkeys (WebAuthn) on the
-- merchant portal, used to sign in and as a step-up on top of the PIN for every sensitive action.

-- Passkeys: several per merchant, each with its own label. The public key is the COSE key the authenticator made; the
-- private key never leaves the merchant's device. The user handle sent to authenticators is the merchant id's 16 bytes.
CREATE TABLE ramp.merchant_passkeys (
    passkey_id      uuid        PRIMARY KEY,                -- UUIDv7
    merchant_id     uuid        NOT NULL,
    credential_id   bytea       NOT NULL,
    public_key_cose bytea       NOT NULL,
    sign_count      bigint      NOT NULL DEFAULT 0 CHECK (sign_count >= 0),
    label           text        NOT NULL,
    transports      text        NOT NULL DEFAULT '',        -- comma-separated, as the browser reported them
    backup_eligible boolean     NOT NULL DEFAULT false,
    backed_up       boolean     NOT NULL DEFAULT false,
    created_at      timestamptz NOT NULL,
    last_used_at    timestamptz
);
CREATE UNIQUE INDEX merchant_passkeys_credential_key ON ramp.merchant_passkeys (credential_id);
CREATE INDEX merchant_passkeys_merchant_idx ON ramp.merchant_passkeys (merchant_id, created_at);

-- A merchant's security state and email preferences, beside (not on) `merchants`. A lock stops every new withdrawal
-- and address until staff lift it; `locked_alert_id` is the alert whose link locked it (null when staff did).
CREATE TABLE ramp.merchant_security (
    merchant_id         uuid        PRIMARY KEY,
    order_emails        boolean     NOT NULL DEFAULT true,   -- an email for each completed order and top-up
    daily_summary       boolean     NOT NULL DEFAULT false,  -- one email a day with the day's trading
    locked_at           timestamptz,
    locked_alert_id     uuid,
    locked_cancelled    integer     NOT NULL DEFAULT 0,      -- withdrawals the lock cancelled
    unlocked_at         timestamptz,
    unlocked_by         text,
    unlock_reason       text,
    updated_at          timestamptz NOT NULL
);
CREATE INDEX merchant_security_daily_idx ON ramp.merchant_security (merchant_id) WHERE daily_summary;

-- Alerts to merchants: written in the same transaction as what they report, sent by the app's `ramp.merchant-alerts`
-- job. `dedupe_key` makes each event one email however often it is written; the send's idempotency key is the alert
-- id. A security alert's "This wasn't me" link is signed from the alert id (HMAC under a key derived from the master
-- key), lasts `link_expires_at`, and works once (`link_used_at`).
CREATE TABLE ramp.merchant_alerts (
    alert_id        uuid        PRIMARY KEY,                 -- UUIDv7
    merchant_id     uuid        NOT NULL,
    kind            text        NOT NULL,
    security        boolean     NOT NULL,                    -- always sent; never switched off
    dedupe_key      text        NOT NULL,
    facts           text        NOT NULL,                    -- the email's facts (JSON, typed in the code)
    state           text        NOT NULL CHECK (state IN ('PENDING', 'SENT', 'SKIPPED', 'FAILED')),
    attempts        integer     NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL,
    failure         text,
    created_at      timestamptz NOT NULL,
    sent_at         timestamptz,
    link_expires_at timestamptz,
    link_used_at    timestamptz
);
CREATE UNIQUE INDEX merchant_alerts_dedupe_key ON ramp.merchant_alerts (dedupe_key);
CREATE INDEX merchant_alerts_pending_idx ON ramp.merchant_alerts (next_attempt_at) WHERE state = 'PENDING';
CREATE INDEX merchant_alerts_merchant_idx ON ramp.merchant_alerts (merchant_id, created_at DESC);

-- Staff's security settings, one row. `merchant_passkey_required`: a merchant without a passkey can't add or remove
-- an address, withdraw, change their PIN or change their payment or payout account until they add one.
CREATE TABLE ramp.security_settings (
    id                        smallint    PRIMARY KEY CHECK (id = 1),
    merchant_passkey_required boolean     NOT NULL DEFAULT false,
    version                   bigint      NOT NULL DEFAULT 1,
    updated_by                text,
    reason                    text,
    updated_at                timestamptz NOT NULL
);

-- An address added before a lock that was still cooling off is frozen: never usable (remove it and add it again).
ALTER TABLE ramp.withdrawal_destinations ADD COLUMN frozen_at timestamptz;

-- A lock (or removing the address) cancels a withdrawal the ops process has not claimed for sending yet. The claim is
-- written just before the custodian is asked, so a claimed one is never cancelled.
ALTER TABLE ramp.withdrawals ADD COLUMN send_claimed_at timestamptz;
ALTER TABLE ramp.withdrawals DROP CONSTRAINT withdrawals_state_check;
ALTER TABLE ramp.withdrawals
    ADD CONSTRAINT withdrawals_state_check CHECK (state IN ('REQUESTED', 'SENT', 'COMPLETED', 'FAILED', 'CANCELLED'));
