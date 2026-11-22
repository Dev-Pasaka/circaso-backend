-- The ramp's first tables (decision 2026-11-22: an automated P2P merchant on-ramp, USDC for KES over M-Pesa): each
-- network's settings, the partner merchants and their portal sessions, their float wallets and sealed payment
-- credentials, Circaso's own ramp wallet, the quotes, the orders, the float reserved for open orders, the commission
-- each completed order earned and the daily sweeps of Circaso's share. Everything about money carries its network
-- (decision 2026-11-18). Verifications, blocks and withdrawals arrive with Didit and the off-ramp. Never edit this
-- script once applied; a change is a new script.

CREATE TABLE ramp.settings (                             -- one row per network; edited in the admin, seeded once
    network                text        PRIMARY KEY CHECK (network IN ('MAINNET', 'TESTNET')),
    state                  text        NOT NULL CHECK (state IN ('OFF', 'PREVIEW', 'ON')),
    chains                 text        NOT NULL,         -- where bought USDC is delivered, comma separated
    order_min_minor        bigint      NOT NULL CHECK (order_min_minor > 0),   -- KES minor units
    order_max_minor        bigint      NOT NULL,
    daily_limit_minor      bigint      NOT NULL,
    prompts_per_hour       integer     NOT NULL CHECK (prompts_per_hour > 0),
    fee_bps                integer     NOT NULL CHECK (fee_bps BETWEEN 0 AND 500),             -- what the user pays
    merchant_share_bps     integer     NOT NULL CHECK (merchant_share_bps BETWEEN 0 AND 10000), -- the rest is Circaso's
    fee_settle_hour_utc    integer     NOT NULL CHECK (fee_settle_hour_utc BETWEEN 0 AND 23),  -- the daily sweep
    quote_seconds          integer     NOT NULL CHECK (quote_seconds > 0),
    payment_window_seconds integer     NOT NULL CHECK (payment_window_seconds > 0),
    kyc_required           boolean     NOT NULL,         -- false only on the testnet, until Didit is built
    callback_base_url      text        NOT NULL,         -- the public origin merchants' callback addresses are made on
    collections_provider   text        NOT NULL,         -- who sends the M-Pesa prompts (payhero); switched in the admin
    wallet_set_id          text,                         -- the merchants' wallet set at the custodian; ops writes it once
    updated_by             text,
    updated_at             timestamptz NOT NULL,
    version                bigint      NOT NULL DEFAULT 0,
    CONSTRAINT settings_limits_check CHECK (order_max_minor >= order_min_minor AND daily_limit_minor >= order_min_minor),
    CONSTRAINT settings_kyc_check CHECK (kyc_required OR network = 'TESTNET')
);

CREATE TABLE ramp.preview_access (                       -- who may trade while a network's ramp is PREVIEW
    user_id    uuid        NOT NULL,
    network    text        NOT NULL,
    granted_by text,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, network)
);
CREATE INDEX preview_access_network_idx ON ramp.preview_access (network, created_at DESC);

CREATE TABLE ramp.merchants (
    merchant_id       uuid        PRIMARY KEY,           -- UUIDv7; a merchant account of its own, not a user account
    contact_email     text        NOT NULL UNIQUE,       -- one merchant account per email; the sign-in
    display_name      text        NOT NULL DEFAULT '',   -- empty until the merchant applies
    contact_phone     text        NOT NULL DEFAULT '',
    state             text        NOT NULL CHECK (state IN ('NEW', 'APPLIED', 'APPROVED', 'SUSPENDED')),
    callback_key      text        UNIQUE,                -- the end of the merchant's own callback address; NULL until RM5
    online_until      timestamptz,                       -- the end of the trading window; NULL when offline
    payout_mode       text        NOT NULL DEFAULT 'NONE', -- NONE | MANUAL | PROVIDER (off-ramp, later)
    last_assigned_at  timestamptz,                       -- the engine's fairness tie-breaker
    approved_by       text,
    approved_at       timestamptz,
    created_at        timestamptz NOT NULL,
    version           bigint      NOT NULL DEFAULT 0
);
CREATE INDEX merchants_online_idx ON ramp.merchants (online_until)
    WHERE state = 'APPROVED' AND online_until IS NOT NULL;          -- the engine's candidates; ramp.end-sessions

CREATE TABLE ramp.merchant_sessions (                    -- portal sign-ins; only a SHA-256 of the token is kept
    token_hash  bytea       PRIMARY KEY,
    merchant_id uuid        NOT NULL,
    created_at  timestamptz NOT NULL,
    expires_at  timestamptz NOT NULL                     -- filtered on read, purged by ramp.purge-expired
);
CREATE INDEX merchant_sessions_merchant_idx ON ramp.merchant_sessions (merchant_id);
CREATE INDEX merchant_sessions_expires_idx ON ramp.merchant_sessions (expires_at);

CREATE TABLE ramp.merchant_wallets (                     -- a merchant's float wallet, one per network
    merchant_id       uuid        NOT NULL,
    network           text        NOT NULL,
    blockchain        text        NOT NULL,
    custody_wallet_id text        NOT NULL UNIQUE,       -- the custodian's wallet id
    wallet_address    text        NOT NULL,
    created_at        timestamptz NOT NULL,
    PRIMARY KEY (merchant_id, network)
);

CREATE TABLE ramp.revenue_wallets (                      -- Circaso's own ramp wallet, one per network; made by ops
    network           text        PRIMARY KEY,
    blockchain        text        NOT NULL,
    custody_wallet_id text        NOT NULL UNIQUE,
    wallet_address    text        NOT NULL,
    created_at        timestamptz NOT NULL
);

CREATE TABLE ramp.merchant_credentials (                 -- sealed; never plaintext
    merchant_id  uuid        PRIMARY KEY,
    provider     text        NOT NULL,                   -- whose account these are (payhero): the network's provider when sealed
    channel_id   text        NOT NULL,                   -- the till or paybill, for display: not a secret
    sealed       bytea       NOT NULL,                   -- AES-256-GCM(data key) over the credentials
    wrapped_key  bytea       NOT NULL,                   -- the data key under KDF(master key, Argon2id(PIN, salt))
    pin_salt     bytea       NOT NULL,
    kdf_params   text        NOT NULL,                   -- Argon2id memory, iterations, parallelism; the master key's version
    updated_at   timestamptz NOT NULL
);

CREATE TABLE ramp.quotes (
    quote_id           uuid        PRIMARY KEY,
    user_id            uuid        NOT NULL,
    network            text        NOT NULL,
    direction          text        NOT NULL,             -- ON_RAMP | OFF_RAMP
    blockchain         text        NOT NULL,
    fiat_currency      text        NOT NULL,             -- KES
    fiat_amount_minor  bigint      NOT NULL CHECK (fiat_amount_minor > 0),
    asset              text        NOT NULL,             -- USDC
    asset_amount_minor bigint      NOT NULL CHECK (asset_amount_minor > 0),
    rate               text        NOT NULL,             -- KES per USDC, a decimal string
    fee_minor          bigint      NOT NULL CHECK (fee_minor >= 0),   -- in the fiat currency, inside what is paid
    fee_bps            integer     NOT NULL,             -- the rate and the split in force when it was priced
    merchant_share_bps integer     NOT NULL,
    expires_at         timestamptz NOT NULL              -- filtered on read, purged by ramp.purge-expired
);
CREATE INDEX quotes_expires_idx ON ramp.quotes (expires_at);

CREATE TABLE ramp.orders (
    order_id            uuid        PRIMARY KEY,         -- UUIDv7; the provider's external reference and the custodian's refId
    user_id             uuid        NOT NULL,
    merchant_id         uuid        NOT NULL,
    quote_id            uuid        NOT NULL UNIQUE,     -- a quote makes one order: a repeated request finds it
    network             text        NOT NULL,
    direction           text        NOT NULL,
    state               text        NOT NULL,
    blockchain          text        NOT NULL,
    fiat_currency       text        NOT NULL,
    fiat_amount_minor   bigint      NOT NULL CHECK (fiat_amount_minor > 0),
    asset               text        NOT NULL,
    asset_amount_minor  bigint      NOT NULL CHECK (asset_amount_minor > 0),
    rate                text        NOT NULL,
    fee_minor           bigint      NOT NULL,
    fee_bps             integer     NOT NULL,            -- its quote's: a later change of the settings doesn't touch it
    merchant_share_bps  integer     NOT NULL,
    collections_provider text       NOT NULL,            -- the provider that prompted it: its status is asked there
    payer_phone         text        NOT NULL,            -- E.164: needed to match the payment; never logged
    destination_address text        NOT NULL,            -- on-ramp: the user's wallet
    provider_reference  text,                            -- the provider's reference for the prompt: the status query's key
    checkout_id         text,
    mpesa_receipt       text,                            -- set when the payment is verified
    custody_tx_id       text,                            -- the custodian's transaction for the release
    tx_hash             text,
    failure_reason      text,
    payment_deadline    timestamptz NOT NULL,            -- when an unpaid prompt stops holding float (the row stays)
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL
);
CREATE INDEX orders_user_idx ON ramp.orders (user_id, network, created_at DESC, order_id DESC);
CREATE INDEX orders_merchant_idx ON ramp.orders (merchant_id, network, created_at DESC, order_id DESC);
CREATE UNIQUE INDEX orders_user_open_key ON ramp.orders (user_id)
    WHERE state IN ('RESERVED', 'PAYMENT_REQUESTED', 'PAID', 'RELEASING',
                    'AWAITING_TRANSFER', 'FUNDED', 'PAYING_OUT', 'PAYOUT_DUE', 'PAYOUT_CLAIMED');
                                                         -- one open order per user
CREATE INDEX orders_open_idx ON ramp.orders (state, updated_at)
    WHERE state NOT IN ('COMPLETED', 'EXPIRED', 'FAILED', 'CANCELLED', 'REFUNDED', 'REVIEW');
                                                         -- ramp.expire-orders, ramp.release
CREATE INDEX orders_review_idx ON ramp.orders (order_id) WHERE state = 'REVIEW';   -- staff's list
CREATE UNIQUE INDEX orders_receipt_key ON ramp.orders (mpesa_receipt) WHERE mpesa_receipt IS NOT NULL;
                                                         -- one M-Pesa payment releases one order, ever

CREATE TABLE ramp.reservations (
    order_id     uuid        PRIMARY KEY,                -- one reservation per order
    merchant_id  uuid        NOT NULL,
    network      text        NOT NULL,
    amount_minor bigint      NOT NULL CHECK (amount_minor > 0),
    asset        text        NOT NULL,                   -- USDC
    kind         text        NOT NULL,                   -- FLOAT (promised to an on-ramp) | HOLD (off-ramp, later)
    state        text        NOT NULL,                   -- OPEN | CONSUMED | RELEASED
    created_at   timestamptz NOT NULL,
    closed_at    timestamptz
);
CREATE INDEX reservations_open_idx ON ramp.reservations (merchant_id, network) WHERE state = 'OPEN';   -- free float

CREATE TABLE ramp.commissions (
    order_id             uuid        PRIMARY KEY,        -- written once, with COMPLETED
    merchant_id          uuid        NOT NULL,
    network              text        NOT NULL,
    merchant_minor       bigint      NOT NULL CHECK (merchant_minor >= 0),   -- the merchant's share of the fee, fiat
    platform_minor       bigint      NOT NULL CHECK (platform_minor >= 0),   -- Circaso's share, fiat
    platform_asset_minor bigint      NOT NULL CHECK (platform_asset_minor >= 0),
                                                         -- Circaso's share in USDC at the order's rate: what the float owes
    settlement_id        uuid,                           -- the sweep that is collecting or collected it; NULL while unswept
    settled_at           timestamptz,                    -- when that sweep was confirmed; NULL while the float still holds it
    currency             text        NOT NULL,
    earned_at            timestamptz NOT NULL
);
CREATE INDEX commissions_merchant_idx ON ramp.commissions (merchant_id, network, earned_at DESC, order_id DESC);
                                                         -- the earnings overview and the statement
CREATE INDEX commissions_owed_idx ON ramp.commissions (merchant_id, network) WHERE settled_at IS NULL;
                                                         -- what the float still holds for Circaso; the sweep
CREATE INDEX commissions_settlement_idx ON ramp.commissions (settlement_id) WHERE settlement_id IS NOT NULL;

CREATE TABLE ramp.fee_settlements (                      -- one daily sweep of what a merchant owes Circaso
    settlement_id  uuid        PRIMARY KEY,              -- the custodian's refId; its idempotency key is derived from it
    merchant_id    uuid        NOT NULL,
    network        text        NOT NULL,
    day            date        NOT NULL,                 -- the UTC day it was opened on
    amount_minor   bigint      NOT NULL CHECK (amount_minor > 0),   -- USDC
    orders         integer     NOT NULL,                 -- how many orders it covers
    state          text        NOT NULL CHECK (state IN ('PENDING', 'CONFIRMED', 'FAILED')),
    custody_tx_id  text,
    tx_hash        text,
    failure_reason text,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL
);
CREATE UNIQUE INDEX fee_settlements_open_key ON ramp.fee_settlements (merchant_id, network) WHERE state = 'PENDING';
CREATE INDEX fee_settlements_merchant_idx ON ramp.fee_settlements (merchant_id, network, day DESC, settlement_id DESC);
CREATE INDEX fee_settlements_pending_idx ON ramp.fee_settlements (network) WHERE state = 'PENDING';

-- The frames the ramp sent, kept 7 days for replay (daily partitions made and dropped by ramp.purge-expired).
CREATE TABLE ramp.realtime (
    user_id  uuid        NOT NULL,
    seq      bigint      NOT NULL,
    frame    text        NOT NULL,
    sent_at  timestamptz NOT NULL,
    PRIMARY KEY (user_id, seq, sent_at)
) PARTITION BY RANGE (sent_at);

-- The shared outbox shape (architecture.md → Durable events).
CREATE TABLE ramp.outbox (
    event_id     uuid        NOT NULL,
    occurred_at  timestamptz NOT NULL,
    type         text        NOT NULL,
    payload      text        NOT NULL,
    published_at timestamptz,
    PRIMARY KEY (event_id, occurred_at)
) PARTITION BY RANGE (occurred_at);
CREATE INDEX outbox_unpublished_idx ON ramp.outbox (occurred_at) WHERE published_at IS NULL;
