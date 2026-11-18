-- Wallet networks (decision 2026-11-18): one server talks to Circle's testnet and mainnet side by side, and every row
-- about money says which network it is on. Everything that existed before this script is testnet. Never edit this
-- script once applied.
--
-- Safe on the live database: nothing is deleted or rewritten except by adding columns. The constant-default columns
-- are metadata-only changes; the generated columns rewrite their (small) tables once under a brief exclusive lock.

-- A chain's network, read from Circle's name for it: a test network's name ends in one of these, and names never
-- overlap between the two networks. The same rule as `Network.ofChain` in the code and in the apps.
CREATE FUNCTION wallet.network_of(blockchain text) RETURNS text LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE WHEN upper(blockchain) ~ '(SEPOLIA|AMOY|DEVNET|FUJI|TESTNET)$' THEN 'TESTNET' ELSE 'MAINNET' END
$$;

-- A holder has one Circle user per network: mainnet is a separate Circle account, so a new wallet and a new PIN.
-- The default stays for good: a server from before this script, still running while it is applied, writes testnet.
ALTER TABLE wallet.circle_users
    ADD COLUMN network text NOT NULL DEFAULT 'TESTNET' CHECK (network IN ('MAINNET', 'TESTNET'));
ALTER TABLE wallet.circle_users
    DROP CONSTRAINT circle_users_pkey,
    ADD PRIMARY KEY (user_id, network);

-- Rows that name their chain get their network from it, so the two can never disagree.
ALTER TABLE wallet.wallets
    ADD COLUMN network text NOT NULL GENERATED ALWAYS AS (wallet.network_of(blockchain)) STORED;
ALTER TABLE wallet.transfers
    ADD COLUMN network text NOT NULL GENERATED ALWAYS AS (wallet.network_of(blockchain)) STORED;
ALTER TABLE wallet.transactions
    ADD COLUMN network text NOT NULL GENERATED ALWAYS AS (wallet.network_of(blockchain)) STORED;
ALTER TABLE wallet.fee_quotes
    ADD COLUMN network text NOT NULL GENERATED ALWAYS AS (wallet.network_of(blockchain)) STORED;
ALTER TABLE wallet.fee_ledger
    ADD COLUMN network text NOT NULL GENERATED ALWAYS AS (wallet.network_of(blockchain)) STORED;

-- A group (or space) has one wallet per network, each with its own wallet id, so everything filed under that id is
-- on one network already.
ALTER TABLE wallet.wallet_owners
    ADD COLUMN network text NOT NULL DEFAULT 'TESTNET' CHECK (network IN ('MAINNET', 'TESTNET'));
DROP INDEX wallet.wallet_owners_chat_key;
CREATE UNIQUE INDEX wallet_owners_chat_key ON wallet.wallet_owners (chat_id, network) WHERE chat_id IS NOT NULL;
DROP INDEX wallet.wallet_owners_space_key;
CREATE UNIQUE INDEX wallet_owners_space_key ON wallet.wallet_owners (space_id, network) WHERE space_id IS NOT NULL;

-- A hold pauses payments on the network whose PIN Vault was opened.
ALTER TABLE wallet.payment_holds
    ADD COLUMN network text NOT NULL DEFAULT 'TESTNET' CHECK (network IN ('MAINNET', 'TESTNET'));
ALTER TABLE wallet.payment_holds
    DROP CONSTRAINT payment_holds_pkey,
    ADD PRIMARY KEY (user_id, network);

-- A limit rule may name one network; NULL (every rule so far) applies on both. What was spent is always counted per
-- network, whichever rule applies.
ALTER TABLE wallet.limit_rules
    ADD COLUMN network text CHECK (network IN ('MAINNET', 'TESTNET'));

-- Reports and the jobs filter by network.
CREATE INDEX transfers_network_newest_idx ON wallet.transfers (network, created_at DESC, transfer_id DESC);
CREATE INDEX transactions_network_occurred_idx ON wallet.transactions (network, occurred_at);

-- Who may use which network beyond the general rule (mainnet for everyone once it is ON): a testnet row keeps the
-- Mainnet | Testnet switch for people who had a testnet wallet, a mainnet row lets staff in while it is in PREVIEW.
CREATE TABLE wallet.network_access (
    user_id    uuid        NOT NULL,
    network    text        NOT NULL CHECK (network IN ('MAINNET', 'TESTNET')),
    source     text        NOT NULL CHECK (source IN ('GRANDFATHERED', 'ADMIN', 'AUTO')),
    granted_by uuid,                                    -- the staff member, for ADMIN
    reason     text,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, network)
);

-- Everyone with a wallet today keeps the testnet: each person with a Circle user of their own, and each person who
-- signs for a group's wallet (a group wallet's own holder id is not a person and gets no row).
INSERT INTO wallet.network_access (user_id, network, source, created_at)
SELECT c.user_id, 'TESTNET', 'GRANDFATHERED', now()
FROM wallet.circle_users c
WHERE NOT EXISTS (SELECT 1 FROM wallet.wallet_owners o WHERE o.wallet_id = c.user_id)
UNION
SELECT o.signer_id, 'TESTNET', 'GRANDFATHERED', now()
FROM wallet.wallet_owners o
ON CONFLICT DO NOTHING;

-- The settings that used to be environment variables (decision 2026-11-18: the environment holds keys and secrets
-- only). The server copies its built-in defaults and the old variables in once, when a network has no row; after
-- that these tables win, and staff edit them from the admin.
CREATE TABLE wallet.network_settings (
    network       text        PRIMARY KEY CHECK (network IN ('MAINNET', 'TESTNET')),
    state         text        NOT NULL CHECK (state IN ('OFF', 'PREVIEW', 'ON')),
    app_id        text        NOT NULL,                 -- Circle's app id for the phone's SDK; empty until set
    default_chain text        NOT NULL,                 -- where a new wallet starts
    version       bigint      NOT NULL DEFAULT 1,       -- one more on each change
    updated_by    uuid,                                 -- the staff member; NULL for the seed
    updated_at    timestamptz NOT NULL
);

CREATE TABLE wallet.network_chains (
    blockchain      text    PRIMARY KEY,                -- Circle's name; never the same on both networks
    network         text    NOT NULL REFERENCES wallet.network_settings (network),
    position        integer NOT NULL,                   -- the order chains are offered in
    enabled         boolean NOT NULL,                   -- false: not offered at all (only while no wallet is on it)
    receive_enabled boolean NOT NULL,                   -- false: retired; it only empties
    account_type    text    NOT NULL CHECK (account_type IN ('SCA', 'EOA')),
    CHECK (wallet.network_of(blockchain) = network)
);
CREATE INDEX network_chains_network_idx ON wallet.network_chains (network, position);

CREATE TABLE wallet.network_assets (
    blockchain    text     NOT NULL REFERENCES wallet.network_chains (blockchain) ON DELETE CASCADE,
    position      integer  NOT NULL,
    symbol        text     NOT NULL,
    token_address text     NOT NULL,                    -- empty for the chain's own coin
    decimals      smallint NOT NULL CHECK (decimals BETWEEN 0 AND 36),
    usd_pegged    boolean  NOT NULL,
    PRIMARY KEY (blockchain, position)
);

-- Circaso's fee wallet per chain, and the fallback dollar price of the chain's own coin. Seeded once from
-- WALLET_FEES_CHAIN_<CHAIN>_ADDRESS and _NATIVE_USD; a chain with no address takes no fee.
CREATE TABLE wallet.chain_fees (
    blockchain  text          PRIMARY KEY,
    network     text          NOT NULL GENERATED ALWAYS AS (wallet.network_of(blockchain)) STORED,
    fee_address text,
    native_usd  numeric(20,6) CHECK (native_usd > 0),
    updated_at  timestamptz   NOT NULL
);
