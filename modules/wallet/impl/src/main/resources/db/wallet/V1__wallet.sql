-- Wallet's tables for WS2 (wallet.md → Data): the Circle user behind each wallet, the user's wallet on each chain, and
-- the outbox. Transfers, transactions and payment holds arrive with WS3 and WS4 as new scripts. Never edit this script
-- once applied; a change is a new V2.

CREATE TABLE wallet.circle_users (                      -- one per user: the wallet itself, whatever chains it is on
    user_id        uuid        PRIMARY KEY,
    circle_user_id uuid        NOT NULL UNIQUE,         -- random; what Circle knows us by, never our user id
    created_at     timestamptz NOT NULL
);

CREATE TABLE wallet.wallets (                           -- one per chain the user's wallet is on
    circle_wallet_id text        PRIMARY KEY,           -- webhooks carry Circle's wallet id
    user_id          uuid        NOT NULL,
    blockchain       text        NOT NULL,              -- Circle's name: BASE | BASE-SEPOLIA | MATIC | SOL | …
    account_type     text        NOT NULL,              -- SCA on EVM chains, EOA on Solana
    address          text        NOT NULL,              -- EVM: lowercase hex; Solana: base58 as given
    state            text        NOT NULL,              -- ACTIVE | FROZEN | CLOSED
    created_at       timestamptz NOT NULL
);
CREATE UNIQUE INDEX wallets_user_chain_key ON wallet.wallets (user_id, blockchain);  -- one wallet per user per chain
CREATE UNIQUE INDEX wallets_address_key ON wallet.wallets (address, blockchain);    -- inbound webhooks, paying a contact

-- The shared outbox shape (architecture.md → Durable events).
CREATE TABLE wallet.outbox (
    event_id     uuid        NOT NULL,
    occurred_at  timestamptz NOT NULL,
    type         text        NOT NULL,
    payload      text        NOT NULL,
    published_at timestamptz,
    PRIMARY KEY (event_id, occurred_at)
) PARTITION BY RANGE (occurred_at);                     -- daily partitions, dropped after 7 days
CREATE INDEX outbox_unpublished_idx ON wallet.outbox (occurred_at) WHERE published_at IS NULL;
