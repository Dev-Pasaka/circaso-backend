-- Provider fees passed to the customer on both sides (2026-11-24, Pasaka: PayHero's collection fee and FinoQ's payout
-- fee are passed to the customer).

-- Buying: the order's `fiat_amount_minor` is now the total prompted (the merchant's quote plus PayHero's fee for that
-- total); `provider_fee_minor` is that fee and `provider_fee_source` where it came from, as selling already kept them.

-- Staff's fallback table for PayHero's prompt fee, used only when PayHero's published table can't be read; seeded from
-- PayHero's `GET /api/transaction_fees` of 2026-11-24 (its upside-down 35000-34999 row left out). JSON, as
-- `payout_fee_bands`: [{upToMinor, feeMinor}], KES cents as strings, an amount up to `upToMinor` inclusive.
ALTER TABLE ramp.settings
    ADD COLUMN collection_fee_bands text NOT NULL DEFAULT '[]';

UPDATE ramp.settings SET collection_fee_bands = '[{"upToMinor":"1000","feeMinor":"0"},{"upToMinor":"4900","feeMinor":"100"},{"upToMinor":"49900","feeMinor":"600"},{"upToMinor":"99900","feeMinor":"1000"},{"upToMinor":"149900","feeMinor":"1500"},{"upToMinor":"249900","feeMinor":"2000"},{"upToMinor":"349900","feeMinor":"2500"},{"upToMinor":"499900","feeMinor":"3000"},{"upToMinor":"749900","feeMinor":"4000"},{"upToMinor":"999900","feeMinor":"4500"},{"upToMinor":"1499900","feeMinor":"5000"},{"upToMinor":"1999900","feeMinor":"5500"},{"upToMinor":"3499900","feeMinor":"8000"},{"upToMinor":"4999900","feeMinor":"10500"},{"upToMinor":"14999900","feeMinor":"13000"},{"upToMinor":"24999900","feeMinor":"16000"},{"upToMinor":"34999900","feeMinor":"19500"},{"upToMinor":"54999900","feeMinor":"23000"},{"upToMinor":"74999900","feeMinor":"27500"},{"upToMinor":"99999900","feeMinor":"32000"}]'
    WHERE collection_fee_bands = '[]';

-- The payout table was empty everywhere (so an unreadable FinoQ pricing page stopped every sale); seeded from FinoQ's
-- phone bands at `api.payhero.africa/api/pricing` of 2026-11-24, each band charged its fee at its last amount.
UPDATE ramp.settings SET payout_fee_bands = '[{"upToMinor":"10000","feeMinor":"80"},{"upToMinor":"50000","feeMinor":"1100"},{"upToMinor":"100000","feeMinor":"1900"},{"upToMinor":"250000","feeMinor":"3900"},{"upToMinor":"1000000","feeMinor":"10400"},{"upToMinor":"2000000","feeMinor":"18900"},{"upToMinor":"25000000","feeMinor":"203900"}]'
    WHERE payout_fee_bands = '[]';

-- One vocabulary for where a provider fee came from: LIVE, CACHED or STAFF_TABLE.
UPDATE ramp.quotes SET provider_fee_source = 'LIVE' WHERE provider_fee_source = 'LIVE_TABLE';
UPDATE ramp.quotes SET provider_fee_source = 'STAFF_TABLE' WHERE provider_fee_source = 'ADMIN_TABLE';
UPDATE ramp.orders SET provider_fee_source = 'LIVE' WHERE provider_fee_source = 'LIVE_TABLE';
UPDATE ramp.orders SET provider_fee_source = 'STAFF_TABLE' WHERE provider_fee_source = 'ADMIN_TABLE';
UPDATE ramp.test_payouts SET provider_fee_source = 'LIVE' WHERE provider_fee_source = 'LIVE_TABLE';
UPDATE ramp.test_payouts SET provider_fee_source = 'STAFF_TABLE' WHERE provider_fee_source = 'ADMIN_TABLE';

-- A test payout also reads FinoQ's service wallet before and after: the payout fee may be taken from there rather
-- than from the payment wallet, and `charged` read off the payment wallet alone would then show 0.
ALTER TABLE ramp.test_payouts
    ADD COLUMN service_before_minor  bigint,
    ADD COLUMN service_before_source text,
    ADD COLUMN service_after_minor   bigint,
    ADD COLUMN service_after_source  text;
