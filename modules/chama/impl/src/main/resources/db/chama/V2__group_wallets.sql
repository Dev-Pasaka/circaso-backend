-- Chamas become groups with wallets (Spaces phase 2, decision 2026-11-03): the money sits in the group's own wallet
-- (wallet.wallet_owners), not a treasurer's; a target is optional on any group with a wallet; payouts are approved
-- by the admins as the group chose (two admins, or any admin) and paid by the wallet's signer. Dev runs on testnet, so
-- no money moves: the goals made under the treasurer model stay as targets waiting for their group's wallet, their
-- contributions stay in the ledger marked `legacy` (paid to a treasurer, not counted towards a target), and the
-- requests still open close as EXPIRED (no treasurer pays them any more). Never edit this script once applied.

ALTER TABLE chama.goals ALTER COLUMN treasurer_id DROP NOT NULL;   -- only on goals from before group wallets

ALTER TABLE chama.contributions ADD COLUMN legacy boolean NOT NULL DEFAULT false;
UPDATE chama.contributions SET legacy = true;

UPDATE chama.requests SET state = 'EXPIRED', updated_at = now() WHERE state IN ('PENDING', 'APPROVED');

-- What the group chose at creation (GW1 "Who approves payouts"), changed later by its owner only. A group with no row
-- uses the default, two admins (every admin when there are fewer).
CREATE TABLE chama.group_settings (
    chat_id          uuid        PRIMARY KEY,           -- messaging's group chat
    approval_policy  text        NOT NULL CHECK (approval_policy IN ('TWO_ADMINS', 'ANY_ADMIN')),
    updated_by       uuid        NOT NULL,
    updated_at       timestamptz NOT NULL
);
