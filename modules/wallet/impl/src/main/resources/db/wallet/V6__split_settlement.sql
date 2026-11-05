-- Split payments settle without a refId (2026-11-05): Circle keeps no refId on a user's contract execution, so the
-- batch's updates find their transfer by the Circle transaction id once it is known, and before that the wallet looks
-- for the transfers no Circle transaction has moved yet. Never edit this script once applied; a change is a new one.

CREATE INDEX transfers_circle_tx_idx ON wallet.transfers (sender_wallet_id, circle_tx_id)
    WHERE circle_tx_id IS NOT NULL;                                          -- byCircleTx
CREATE INDEX transfers_unsettled_split_idx ON wallet.transfers (created_at)
    WHERE fee_address IS NOT NULL AND circle_tx_id IS NULL
      AND state IN ('AWAITING_SIGNATURE', 'EXPIRED');                        -- unsettledSplits, wallet.unsettled-splits
