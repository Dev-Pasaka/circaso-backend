-- An IN row names the Circaso transfer that paid it (2026-11-05): the recipient's phone builds the chat's Received
-- card from the server's record (W6, payment.received) when it can't open the end-to-end payment card. A new column,
-- not transfer_id: transactions_transfer_key keeps transfer_id to the sender's OUT row, which moveByTransfer moves.
-- Never edit this script once applied; a change is a new one.

ALTER TABLE wallet.transactions ADD COLUMN paid_by_transfer_id uuid;
CREATE INDEX transactions_paid_by_idx ON wallet.transactions (paid_by_transfer_id)
    WHERE paid_by_transfer_id IS NOT NULL;                                   -- isPaidBy: a transfer links one IN row
CREATE INDEX transfers_paid_to_idx ON wallet.transfers (recipient_id, sender_wallet_id, created_at)
    WHERE recipient_id IS NOT NULL;                                          -- paidTo: the transfer behind an IN row
