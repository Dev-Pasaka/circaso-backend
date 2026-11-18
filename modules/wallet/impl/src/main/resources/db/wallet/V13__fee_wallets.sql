-- Circaso's fee wallets (decision 2026-11-18, the fee-wallet follow-up): Circle developer-controlled wallets the ops
-- server creates itself at start-up, the destinations staff may withdraw collected fees to, and the withdrawals. The
-- address fees are charged to stays `wallet.chain_fees.fee_address`; these tables say which Circle wallet is behind it.
-- Never edit this script once applied. It only adds tables.

-- Where a network's fee wallets stand, as the last start-up (or the ten-minute check) left them.
CREATE TABLE wallet.fee_wallet_networks (
    network       text        PRIMARY KEY CHECK (network IN ('MAINNET', 'TESTNET')),
    wallet_set_id text,                                 -- Circle's wallet set "Circaso fees"; NULL until known
    status        text        NOT NULL CHECK (status IN ('READY', 'NOT_CREATED', 'FAILED')),
    reason        text,                                 -- why it isn't READY, or what a READY one can't do; never a secret
    updated_at    timestamptz NOT NULL
);

-- The fee wallet on each chain. `circle_wallet_id` NULL: an address fees are collected at whose Circle wallet this
-- server can't find (no key for that account), so its balance isn't read and nothing can be withdrawn.
CREATE TABLE wallet.fee_wallets (
    blockchain       text        PRIMARY KEY,
    network          text        NOT NULL GENERATED ALWAYS AS (wallet.network_of(blockchain)) STORED,
    address          text        NOT NULL,
    circle_wallet_id text        UNIQUE,
    wallet_set_id    text,
    origin           text        NOT NULL CHECK (origin IN ('CREATED', 'IMPORTED', 'ADDRESS_ONLY')),
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL
);

-- Where collected fees may be sent. A destination can be used only once `destination_delay_hours` have passed since
-- it was added, so an address slipped in can be seen and removed first. Removing keeps the row: withdrawals name it.
CREATE TABLE wallet.fee_destinations (
    destination_id   uuid        PRIMARY KEY,
    blockchain       text        NOT NULL,
    network          text        NOT NULL GENERATED ALWAYS AS (wallet.network_of(blockchain)) STORED,
    address          text        NOT NULL,              -- EVM addresses in lower case
    label            text        NOT NULL,
    created_by       uuid        NOT NULL,              -- the staff member
    created_by_email text        NOT NULL,
    created_at       timestamptz NOT NULL,
    removed_at       timestamptz,
    removed_by       uuid
);
CREATE UNIQUE INDEX fee_destinations_live_key ON wallet.fee_destinations (blockchain, address) WHERE removed_at IS NULL;

-- A withdrawal of collected fees: PENDING (recorded, Circle not yet known to have it) -> SENT (Circle has it) ->
-- CONFIRMED | FAILED. `circle_key` is the idempotency key every attempt to send it carries, so it is sent once.
CREATE TABLE wallet.fee_withdrawals (
    withdrawal_id       uuid          PRIMARY KEY,      -- UUIDv7: newest first by id
    blockchain          text          NOT NULL,
    network             text          NOT NULL GENERATED ALWAYS AS (wallet.network_of(blockchain)) STORED,
    asset               text          NOT NULL,
    token_address       text          NOT NULL,         -- empty for the chain's own coin
    decimals            smallint      NOT NULL CHECK (decimals BETWEEN 0 AND 36),
    amount_minor        numeric(78,0) NOT NULL CHECK (amount_minor > 0),
    destination_id      uuid          NOT NULL REFERENCES wallet.fee_destinations (destination_id),
    destination_address text          NOT NULL,
    destination_label   text          NOT NULL,
    circle_wallet_id    text          NOT NULL,
    token_id            text          NOT NULL,         -- Circle's id for the coin
    circle_key          uuid          NOT NULL UNIQUE,
    state               text          NOT NULL CHECK (state IN ('PENDING', 'SENT', 'CONFIRMED', 'FAILED')),
    circle_tx_id        text,
    tx_hash             text,
    failure_reason      text,
    reason              text          NOT NULL,         -- why staff withdrew
    requested_by        uuid          NOT NULL,
    requested_by_email  text          NOT NULL,
    idempotency_key     text          NOT NULL,         -- the request's Idempotency-Key
    created_at          timestamptz   NOT NULL,
    updated_at          timestamptz   NOT NULL,
    UNIQUE (requested_by, idempotency_key),
    CHECK (state NOT IN ('SENT', 'CONFIRMED') OR circle_tx_id IS NOT NULL)
);
CREATE INDEX fee_withdrawals_network_idx ON wallet.fee_withdrawals (network, withdrawal_id DESC);
CREATE INDEX fee_withdrawals_open_idx ON wallet.fee_withdrawals (state) WHERE state IN ('PENDING', 'SENT');

-- Settings for the fee wallets (one row; the environment holds keys and secrets only).
CREATE TABLE wallet.fee_wallet_settings (
    id                      smallint    PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    destination_delay_hours integer     NOT NULL DEFAULT 24 CHECK (destination_delay_hours BETWEEN 0 AND 720),
    updated_at              timestamptz NOT NULL DEFAULT now()
);
INSERT INTO wallet.fee_wallet_settings (id) VALUES (1);
