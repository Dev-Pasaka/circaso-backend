-- The off-ramp (decision 2026-11-23): a user sells USDC and is paid shillings to their own M-Pesa number from a
-- merchant's FinoQ (PayHero) account. The engine routes the sale; the user's USDC is held in the merchant's float until
-- the provider itself says the shillings were paid, and goes back to the user when they can't be.

-- Selling's own switch, limits and windows, and what staff set for the provider's payout fee when its published table
-- can't be read. A sale is open on its own switch: buying's `state` doesn't open it.
ALTER TABLE ramp.settings
    ADD COLUMN offramp_state             text    NOT NULL DEFAULT 'OFF'
        CHECK (offramp_state IN ('OFF', 'PREVIEW', 'ON')),
    ADD COLUMN offramp_min_minor         bigint  NOT NULL DEFAULT 1000 CHECK (offramp_min_minor > 0),  -- KES the user receives
    ADD COLUMN offramp_max_minor         bigint  NOT NULL DEFAULT 500000,
    ADD COLUMN offramp_daily_limit_minor bigint  NOT NULL DEFAULT 2000000,
    ADD COLUMN transfer_window_seconds   integer NOT NULL DEFAULT 900
        CHECK (transfer_window_seconds BETWEEN 60 AND 3600),       -- how long a sale waits for the user's USDC
    ADD COLUMN payout_window_seconds     integer NOT NULL DEFAULT 1800
        CHECK (payout_window_seconds BETWEEN 60 AND 86400),        -- how long a payout nothing went out for is retried
    ADD COLUMN payout_fee_bands          text    NOT NULL DEFAULT '[]',   -- JSON: the admin's fallback fee table
    ADD COLUMN payout_balance_low_minor  bigint  NOT NULL DEFAULT 100000
        CHECK (payout_balance_low_minor >= 0),                     -- below it RM2 flags payout_balance_low; 0: never
    ADD CONSTRAINT settings_offramp_limits_check
        CHECK (offramp_max_minor >= offramp_min_minor AND offramp_daily_limit_minor >= offramp_min_minor);

-- A sale's quote carries the provider's payout fee the user pays on top of Circaso's fee, and where it came from.
ALTER TABLE ramp.quotes
    ADD COLUMN provider_fee_minor  bigint NOT NULL DEFAULT 0 CHECK (provider_fee_minor >= 0),
    ADD COLUMN provider_fee_source text;

-- A sale's order: the payout fee quoted and as the provider's tariff stood when it was sent, the user's transfer and
-- what it brought, where a refund goes, and each payout attempt. `payer_phone` is the number paid (a sale's payee);
-- `destination_address` is the merchant's float address; `mpesa_receipt` is the payout's receipt; `custody_tx_id` and
-- `tx_hash` are the refund's, when there is one.
ALTER TABLE ramp.orders
    ADD COLUMN provider_fee_minor         bigint  NOT NULL DEFAULT 0 CHECK (provider_fee_minor >= 0),
    ADD COLUMN provider_fee_source        text,
    ADD COLUMN provider_fee_actual_minor  bigint,
    ADD COLUMN wallet_transfer_id         uuid,        -- the user's W4 that funds it
    ADD COLUMN funded_minor               bigint,      -- USDC the merchant's wallet received, as the custodian recorded it
    ADD COLUMN funding_tx_hash            text,
    ADD COLUMN funded_at                  timestamptz,
    ADD COLUMN refund_address             text,        -- the user's own wallet the USDC came from
    ADD COLUMN payout_attempts            integer NOT NULL DEFAULT 0 CHECK (payout_attempts >= 0),
    ADD COLUMN payout_failures            integer NOT NULL DEFAULT 0 CHECK (payout_failures >= 0),  -- the provider said FAILED
    ADD COLUMN payout_reference           text,        -- ours for the latest attempt: the order id, then `<id>.2`
    ADD COLUMN payout_provider_reference  text,
    ADD COLUMN payout_secondary_reference text,
    ADD COLUMN payout_outcome             text,        -- SENDING | ACCEPTED | UNKNOWN | NOT_SENT:… | FAILED:… | PAID
    ADD COLUMN payout_sent_at             timestamptz,
    ADD COLUMN payout_retry_at            timestamptz, -- FUNDED: not before then
    ADD COLUMN recheck_requested_at       timestamptz; -- REVIEW: staff asked the app's process to ask the provider again
CREATE UNIQUE INDEX orders_wallet_transfer_key ON ramp.orders (wallet_transfer_id)
    WHERE wallet_transfer_id IS NOT NULL;                           -- one transfer funds one order
CREATE INDEX orders_recheck_idx ON ramp.orders (recheck_requested_at)
    WHERE recheck_requested_at IS NOT NULL;                          -- staff's re-checks, for the app's sweep
CREATE INDEX orders_funding_idx ON ramp.orders (network, updated_at)
    WHERE wallet_transfer_id IS NOT NULL AND funded_minor IS NULL;  -- transfers still to confirm with the custodian

-- One open order per user, now with a refund in flight as open too.
DROP INDEX ramp.orders_user_open_key;
CREATE UNIQUE INDEX orders_user_open_key ON ramp.orders (user_id)
    WHERE state IN ('RESERVED', 'PAYMENT_REQUESTED', 'PAID', 'RELEASING',
                    'AWAITING_TRANSFER', 'FUNDED', 'PAYING_OUT', 'REFUNDING');

-- What a merchant needs for payouts (FinoQ's account id: not a secret) and what the provider last said about them.
ALTER TABLE ramp.merchants
    ADD COLUMN payout_account_id    text CHECK (payout_account_id ~ '^[0-9]{1,18}$'),
    ADD COLUMN payout_issue         text CHECK (payout_issue IN ('PAYOUT_BALANCE', 'PAYOUT_REFUSED')),
    ADD COLUMN payout_issue_at      timestamptz,
    ADD COLUMN payout_balance_minor bigint,       -- the last reading, for staff (the ops process holds no keys)
    ADD COLUMN payout_balance_source text,
    ADD COLUMN fee_balance_minor    bigint,
    ADD COLUMN fee_balance_source   text,
    ADD COLUMN balances_read_at     timestamptz;

-- The shillings promised to open sales at each merchant's provider: the order's amount plus its payout fee, held from
-- the order until it completes or ends. Free shillings = the provider's payout balance − the open holds.
CREATE TABLE ramp.payout_holds (
    order_id     uuid        PRIMARY KEY,
    merchant_id  uuid        NOT NULL,
    amount_minor bigint      NOT NULL CHECK (amount_minor > 0),
    currency     text        NOT NULL,
    state        text        NOT NULL CHECK (state IN ('OPEN', 'CONSUMED', 'RELEASED')),
    created_at   timestamptz NOT NULL,
    closed_at    timestamptz
);
CREATE INDEX payout_holds_open_idx ON ramp.payout_holds (merchant_id) WHERE state = 'OPEN';

-- A merchant's test payout: KES 10 to 100 from their own account to their own contact phone, sent once and followed
-- like a sale's, so a real payout can be tried end to end before anyone sells.
CREATE TABLE ramp.test_payouts (
    test_id              uuid        PRIMARY KEY,   -- UUIDv7; the payout's reference
    merchant_id          uuid        NOT NULL,
    provider             text        NOT NULL,
    amount_minor         bigint      NOT NULL CHECK (amount_minor BETWEEN 1000 AND 10000),
    payee_phone          text        NOT NULL,      -- the merchant's own contact phone; never logged
    state                text        NOT NULL
        CHECK (state IN ('SENDING', 'ACCEPTED', 'UNKNOWN', 'PAID', 'FAILED', 'NOT_SENT', 'NOT_FOUND')),
    outcome              text,                      -- what the send or the last lookup said, in our words
    provider_reference   text,
    secondary_reference  text,
    receipt              text,
    paid_amount_minor    bigint,
    provider_fee_minor   bigint,                    -- the provider's tariff for the amount, when it could be read
    provider_fee_source  text,
    balance_before_minor bigint,
    balance_before_source text,
    balance_after_minor  bigint,
    balance_after_source text,
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL
);
CREATE UNIQUE INDEX test_payouts_open_key ON ramp.test_payouts (merchant_id)
    WHERE state IN ('SENDING', 'ACCEPTED', 'UNKNOWN');                  -- one test in flight per merchant
CREATE INDEX test_payouts_merchant_idx ON ramp.test_payouts (merchant_id, created_at DESC, test_id DESC);
CREATE INDEX test_payouts_open_idx ON ramp.test_payouts (updated_at) WHERE state IN ('SENDING', 'ACCEPTED', 'UNKNOWN');
