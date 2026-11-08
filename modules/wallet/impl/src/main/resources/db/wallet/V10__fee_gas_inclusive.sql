-- Fees gas inclusive (decision 2026-11-08). A fee is now the network cost with the rule's margin on top, plus the
-- rule's rate, raised to min_fee_usd when one is set; gas_share_max_bps no longer prices anything and stays only for
-- compatibility. The network cost is priced on what recent transfers on the chain actually cost (the 75th percentile
-- of the fee ledger's actual_gas_minor), or Circle's estimate while a chain has too little history: gas_source says
-- which priced each quote and transfer (RECENT | ESTIMATE; NULL before this change). Never edit this script once
-- applied.

ALTER TABLE wallet.fee_quotes ADD COLUMN gas_source text CHECK (gas_source IN ('RECENT', 'ESTIMATE'));
ALTER TABLE wallet.fee_ledger ADD COLUMN gas_source text CHECK (gas_source IN ('RECENT', 'ESTIMATE'));

-- What recent transfers cost: collected rows with the chain's reported cost, newest first.
CREATE INDEX fee_ledger_actual_gas_idx ON wallet.fee_ledger (created_at DESC)
    WHERE state = 'COLLECTED' AND actual_gas_minor IS NOT NULL;

-- Spaces were archived on 2026-11-05: the two space fee rules (V4's seeds) end now. Their versions stay, so payments
-- they priced can still be traced; a payment to or from a space wallet now falls to the general INTERNAL rule. Only a
-- version already started can end now (effective_to must be after effective_from).
UPDATE wallet.fee_rules
   SET effective_to = now()
 WHERE rule_id IN ('0195f2f0-0000-7000-8000-000000000004', '0195f2f0-0000-7000-8000-000000000005')
   AND (effective_to IS NULL OR effective_to > now())
   AND effective_from < now();
