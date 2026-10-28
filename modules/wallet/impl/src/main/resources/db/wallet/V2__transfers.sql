-- Wallet's tables for WS3 (wallet.md → Data): transfers, the transaction history they write, and the realtime frames
-- kept for replay. A transfer and its transaction rows name their chain (decision 2026-10-28: a transfer is sent from
-- the wallet on the chain the request names). Payment holds arrive with WS4. Never edit this script once applied; a
-- change is a new script.

CREATE TABLE wallet.transfers (
    transfer_id         uuid        PRIMARY KEY,        -- Circle's refId
    sender_id           uuid        NOT NULL,
    sender_wallet_id    text        NOT NULL,           -- the sender's Circle wallet on this chain
    blockchain          text        NOT NULL,
    idempotency_key     uuid        NOT NULL,           -- from the W4 request's Idempotency-Key; Circle's key too
    recipient_id        uuid,                           -- NULL for an outside address
    destination_address text        NOT NULL,           -- EVM: lowercase hex; Solana: base58 as given
    amount_minor        bigint      NOT NULL CHECK (amount_minor > 0),
    asset               text        NOT NULL,
    state               text        NOT NULL,           -- AWAITING_SIGNATURE | SUBMITTED | CONFIRMED | FAILED | EXPIRED
    circle_tx_id        text,
    tx_hash             text,
    chat_id             uuid,
    origin              text        NOT NULL,           -- APP | ASHA
    proposal_id         uuid,
    failure_reason      text,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    version             bigint      NOT NULL DEFAULT 0  -- read-modify-write edits only; states move by the guard
);
CREATE UNIQUE INDEX transfers_idempotency_key ON wallet.transfers (sender_id, idempotency_key);
CREATE INDEX transfers_open_idx ON wallet.transfers (state, created_at)
    WHERE state IN ('AWAITING_SIGNATURE', 'SUBMITTED');                      -- ExpireUnsignedTransfers, StuckTransfers

CREATE TABLE wallet.transactions (                      -- history and statements
    id                   uuid        PRIMARY KEY,       -- OrderedIds
    user_id              uuid        NOT NULL,
    direction            text        NOT NULL,          -- IN | OUT
    state                text        NOT NULL,
    amount_minor         bigint      NOT NULL,
    asset                text        NOT NULL,
    blockchain           text        NOT NULL,
    counterparty_id      uuid,
    counterparty_address text,
    transfer_id          uuid,                          -- set for outgoing transfers prepared here
    circle_tx_id         text,                          -- NULL until Circle reports an outgoing transfer
    tx_hash              text,
    chat_id              uuid,
    origin               text        NOT NULL DEFAULT 'APP',
    occurred_at          timestamptz NOT NULL
);
CREATE INDEX transactions_user_idx ON wallet.transactions (user_id, occurred_at DESC, id DESC);
                                                        -- W6 and W7: a month is a range of occurred_at, paged by keyset
CREATE UNIQUE INDEX transactions_circle_key ON wallet.transactions (circle_tx_id, user_id);
                                                        -- a webhook finds its rows; a repeated inbound one adds none
CREATE UNIQUE INDEX transactions_transfer_key ON wallet.transactions (transfer_id) WHERE transfer_id IS NOT NULL;
CREATE INDEX transactions_occurred_idx ON wallet.transactions (occurred_at);   -- ReconcileWallets: the day's activity

-- The same shape as users' realtime: the frames sent, kept 7 days for replay.
CREATE TABLE wallet.realtime (
    user_id uuid        NOT NULL,
    seq     bigint      NOT NULL,
    frame   text        NOT NULL,                       -- the envelope as JSON
    sent_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, seq, sent_at)
) PARTITION BY RANGE (sent_at);                         -- daily partitions, dropped after 7 days
