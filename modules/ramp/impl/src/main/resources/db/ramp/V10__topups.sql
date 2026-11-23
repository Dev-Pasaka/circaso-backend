-- A merchant topping up one of their own two wallets at the provider (FinoQ's service wallet, which pays its fees,
-- or its payment wallet, which payouts spend) with an M-Pesa prompt to their own contact phone (2026-11-23). Written
-- before the prompt is asked for and sent once; only the provider's status or the wallet's balance confirms it.
CREATE TABLE ramp.topups (
    topup_id             uuid        PRIMARY KEY,   -- UUIDv7; our reference, where the provider takes one
    merchant_id          uuid        NOT NULL,
    wallet               text        NOT NULL CHECK (wallet IN ('SERVICE', 'PAYMENT')),
    provider             text        NOT NULL,
    amount_minor         bigint      NOT NULL CHECK (amount_minor > 0 AND amount_minor % 100 = 0),   -- whole KES
    payer_phone          text        NOT NULL,      -- the merchant's own contact phone; never logged
    state                text        NOT NULL CHECK (state IN ('REQUESTED', 'CONFIRMED', 'FAILED', 'UNKNOWN')),
    failure              text,                      -- CANCELLED | FAILED | SERVICE_BALANCE_LOW | PAYER_NOT_ALLOWED | …
    outcome              text,                      -- what the provider last said, in our words
    provider_reference   text,
    checkout_id          text,
    receipt              text,                      -- M-Pesa's code, when the provider gives it
    confirmed_by         text        CHECK (confirmed_by IN ('STATUS', 'BALANCE')),
    balance_before_minor bigint,                    -- that wallet's balance before the prompt, when it could be read
    balance_after_minor  bigint,                    -- and once confirmed
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    closed_at            timestamptz
);
CREATE UNIQUE INDEX topups_open_key ON ramp.topups (merchant_id, wallet) WHERE state = 'REQUESTED';   -- one at a time
CREATE INDEX topups_merchant_idx ON ramp.topups (merchant_id, created_at DESC, topup_id DESC);
CREATE INDEX topups_open_idx ON ramp.topups (updated_at) WHERE state = 'REQUESTED';
