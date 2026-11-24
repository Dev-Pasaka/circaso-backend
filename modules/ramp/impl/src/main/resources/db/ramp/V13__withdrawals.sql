-- Merchant float withdrawals (2026-11-24): a merchant sends USDC out of their float wallet to an address of their own.
-- An address is saved first (PIN) and can be used only after a cooling-off delay, the main defence if a portal session
-- is stolen. A withdrawal is written REQUESTED with its amount reserved (a `ramp.reservations` row of kind WITHDRAWAL,
-- keyed by the withdrawal id), and the ops process sends it (`ramp.release`), then follows it to COMPLETED or FAILED.

ALTER TABLE ramp.settings
    ADD COLUMN withdraw_destination_delay_hours integer NOT NULL DEFAULT 24
        CHECK (withdraw_destination_delay_hours BETWEEN 0 AND 720),   -- before a new address can be withdrawn to
    ADD COLUMN withdraw_min_minor               bigint  NOT NULL DEFAULT 1000000
        CHECK (withdraw_min_minor > 0),                               -- USDC minor units: 1 USDC
    ADD COLUMN withdraw_daily_max_minor         bigint  NOT NULL DEFAULT 0
        CHECK (withdraw_daily_max_minor >= 0);                        -- per Nairobi day; 0: no cap

CREATE TABLE ramp.withdrawal_destinations (
    destination_id uuid        PRIMARY KEY,                 -- UUIDv7
    merchant_id    uuid        NOT NULL,
    network        text        NOT NULL CHECK (network IN ('MAINNET', 'TESTNET')),
    blockchain     text        NOT NULL,                    -- the float wallet's chain on that network
    address        text        NOT NULL,                    -- EIP-55 checksummed
    label          text        NOT NULL,
    created_at     timestamptz NOT NULL,
    usable_at      timestamptz NOT NULL,                    -- created_at + the delay in force when it was added
    removed_at     timestamptz                              -- kept: withdrawals name it
);
CREATE UNIQUE INDEX withdrawal_destinations_live_key
    ON ramp.withdrawal_destinations (merchant_id, network, lower(address)) WHERE removed_at IS NULL;
CREATE INDEX withdrawal_destinations_merchant_idx
    ON ramp.withdrawal_destinations (merchant_id, network, created_at DESC);

CREATE TABLE ramp.withdrawals (
    withdrawal_id       uuid        PRIMARY KEY,            -- UUIDv7; the custodian's refId; its key is derived from it
    merchant_id         uuid        NOT NULL,
    network             text        NOT NULL CHECK (network IN ('MAINNET', 'TESTNET')),
    blockchain          text        NOT NULL,
    destination_id      uuid        NOT NULL,
    destination_address text        NOT NULL,               -- as it was when requested
    destination_label   text        NOT NULL,
    amount_minor        bigint      NOT NULL CHECK (amount_minor > 0),   -- USDC
    asset               text        NOT NULL,
    state               text        NOT NULL CHECK (state IN ('REQUESTED', 'SENT', 'COMPLETED', 'FAILED')),
    idempotency_key     text,                               -- the portal's Idempotency-Key: a repeat is the same row
    custody_tx_id       text,
    tx_hash             text,
    failure_reason      text,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    sent_at             timestamptz,
    closed_at           timestamptz,
    CONSTRAINT withdrawals_sent_has_tx CHECK (state NOT IN ('SENT', 'COMPLETED') OR custody_tx_id IS NOT NULL)
);
CREATE UNIQUE INDEX withdrawals_idempotency_key
    ON ramp.withdrawals (merchant_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX withdrawals_merchant_idx ON ramp.withdrawals (merchant_id, network, created_at DESC, withdrawal_id DESC);
CREATE INDEX withdrawals_open_idx ON ramp.withdrawals (network, updated_at) WHERE state IN ('REQUESTED', 'SENT');
