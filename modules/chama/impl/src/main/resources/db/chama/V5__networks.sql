-- Wallet networks (decision 2026-11-18): a group's money is kept per network (its mainnet wallet and its testnet
-- wallet are different wallets), so every chama row says which network it is on. Everything that existed before this
-- script is testnet. Constant-default columns only: nothing is rewritten or deleted. Never edit this script once
-- applied.
ALTER TABLE chama.goals
    ADD COLUMN network text NOT NULL DEFAULT 'TESTNET' CHECK (network IN ('MAINNET', 'TESTNET'));
ALTER TABLE chama.goals
    DROP CONSTRAINT goals_pkey,
    ADD PRIMARY KEY (chat_id, network);                -- one target per group per network

ALTER TABLE chama.group_settings
    ADD COLUMN network text NOT NULL DEFAULT 'TESTNET' CHECK (network IN ('MAINNET', 'TESTNET'));
ALTER TABLE chama.group_settings
    DROP CONSTRAINT group_settings_pkey,
    ADD PRIMARY KEY (chat_id, network);

ALTER TABLE chama.contributions
    ADD COLUMN network text NOT NULL DEFAULT 'TESTNET' CHECK (network IN ('MAINNET', 'TESTNET'));
DROP INDEX chama.contributions_chat_idx;
CREATE INDEX contributions_chat_idx ON chama.contributions (chat_id, network, at DESC);

ALTER TABLE chama.requests
    ADD COLUMN network text NOT NULL DEFAULT 'TESTNET' CHECK (network IN ('MAINNET', 'TESTNET'));
DROP INDEX chama.requests_chat_idx;
CREATE INDEX requests_chat_idx ON chama.requests (chat_id, network, created_at DESC);

ALTER TABLE chama.direct_payouts
    ADD COLUMN network text NOT NULL DEFAULT 'TESTNET' CHECK (network IN ('MAINNET', 'TESTNET'));
DROP INDEX chama.direct_payouts_chat_idx;
CREATE INDEX direct_payouts_chat_idx ON chama.direct_payouts (chat_id, network);
