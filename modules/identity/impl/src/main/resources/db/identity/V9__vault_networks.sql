-- Wallet networks (decision 2026-11-18): the mainnet wallet is a separate Circle user with its own PIN, so a person
-- may keep one PIN Vault per network. Every vault that existed before this script holds a testnet PIN. A
-- constant-default column and a wider primary key: nothing is rewritten or deleted. Never edit this script once
-- applied.
ALTER TABLE identity.pin_vaults
    ADD COLUMN network text NOT NULL DEFAULT 'TESTNET' CHECK (network IN ('MAINNET', 'TESTNET'));
ALTER TABLE identity.pin_vaults
    DROP CONSTRAINT pin_vaults_pkey,
    ADD PRIMARY KEY (user_id, network);
