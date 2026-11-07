-- Staff money reads on the ops gateway (decision 2026-11-08): every query of the wallet's `WalletReports` contract is
-- a keyset page or a range aggregate on one of these. Plain CREATE INDEX: Flyway runs each script in a transaction,
-- and the tables are small at this stage, so the short write lock while each builds is acceptable. Never edit this
-- script once applied; a change is a new one.

-- WalletReports.transfers with no person: every transfer newest first, keyset on (created_at, transfer_id).
CREATE INDEX IF NOT EXISTS transfers_newest_idx ON wallet.transfers (created_at DESC, transfer_id DESC);

-- WalletReports.report (confirmed transfers in a range, by period and by chain and coin) and transfers(state =
-- CONFIRMED): only the confirmed rows, in the same order.
CREATE INDEX IF NOT EXISTS transfers_confirmed_idx ON wallet.transfers (created_at DESC, transfer_id DESC)
    WHERE state = 'CONFIRMED';

-- WalletReports.transfersOf (and transfers with a person): the two sides read separately and merged, each newest first.
-- transfers_paid_to_idx (recipient_id, sender_wallet_id, created_at) can't give the recipient's order.
CREATE INDEX IF NOT EXISTS transfers_sender_idx ON wallet.transfers (sender_id, created_at DESC, transfer_id DESC);
CREATE INDEX IF NOT EXISTS transfers_recipient_idx ON wallet.transfers (recipient_id, created_at DESC, transfer_id DESC)
    WHERE recipient_id IS NOT NULL;

-- WalletReports.feesPaidBy: the collected fee legs of one holder.
CREATE INDEX IF NOT EXISTS fee_ledger_collected_idx ON wallet.fee_ledger (holder_id) WHERE state = 'COLLECTED';

-- WalletReports.groupWallets and groupWalletCount: group wallets newest first, keyset on (created_at, wallet_id).
CREATE INDEX IF NOT EXISTS wallet_owners_groups_newest_idx ON wallet.wallet_owners (created_at DESC, wallet_id DESC)
    WHERE owner_type = 'GROUP';
