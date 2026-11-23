-- A merchant's three wallets at their provider (2026-11-24, the owner: "the PayHero service wallet is for on-ramp, the
-- FinoQ service wallet is for off-ramp; you should be able to top up any of them"). Until now one "fee" balance held
-- whichever service wallet answered first (PayHero's, else FinoQ's), so two wallets shared one column.

-- Each wallet's last reading, with its own time: a reading that couldn't read one wallet keeps its last.
ALTER TABLE ramp.merchants
    ADD COLUMN payhero_service_balance_minor  bigint,       -- PayHero's service wallet: buying's prompt fees
    ADD COLUMN payhero_service_balance_source text,
    ADD COLUMN payhero_service_read_at        timestamptz,
    ADD COLUMN finoq_service_balance_minor    bigint,       -- FinoQ's service wallet: selling's payout fees
    ADD COLUMN finoq_service_balance_source   text,
    ADD COLUMN finoq_service_read_at          timestamptz,
    ADD COLUMN payout_balance_read_at         timestamptz;  -- FinoQ's payment wallet keeps payout_balance_minor

-- The old fee reading goes to the wallet its source names; a reading that names neither is dropped.
UPDATE ramp.merchants
SET payhero_service_balance_minor  = fee_balance_minor,
    payhero_service_balance_source = fee_balance_source,
    payhero_service_read_at        = balances_read_at
WHERE fee_balance_minor IS NOT NULL AND fee_balance_source LIKE 'payhero.%';

UPDATE ramp.merchants
SET finoq_service_balance_minor  = fee_balance_minor,
    finoq_service_balance_source = fee_balance_source,
    finoq_service_read_at        = balances_read_at
WHERE fee_balance_minor IS NOT NULL AND fee_balance_source LIKE 'finoq.%';

UPDATE ramp.merchants
SET payout_balance_read_at = balances_read_at
WHERE payout_balance_minor IS NOT NULL;

-- fee_balance_minor and fee_balance_source are no longer written or read; a later script drops them, once no running
-- server from before this one can still write them.
COMMENT ON COLUMN ramp.merchants.fee_balance_minor IS 'Unused since V11: see payhero_service_* and finoq_service_*';

-- Top-ups name the wallet they fill: SERVICE was PayHero's service wallet, PAYMENT FinoQ's payment wallet.
ALTER TABLE ramp.topups DROP CONSTRAINT topups_wallet_check;
UPDATE ramp.topups SET wallet = 'PAYHERO_SERVICE' WHERE wallet = 'SERVICE';
UPDATE ramp.topups SET wallet = 'FINOQ_PAYMENT' WHERE wallet = 'PAYMENT';
ALTER TABLE ramp.topups
    ADD CONSTRAINT topups_wallet_check CHECK (wallet IN ('PAYHERO_SERVICE', 'FINOQ_SERVICE', 'FINOQ_PAYMENT'));
