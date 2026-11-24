-- Amount bands on the ramp fee (decision 2026-11-24, Pasaka: "the band sets the rate"). Each network's buying and
-- selling fees are now each an ordered list of bands by the order's KES amount, [from, to) in KES minor units with its
-- own rate; the band an order falls in sets the rate for the whole amount (buying: what the user pays; selling: the KES
-- value of the USDC sold, which its fee is charged on). The merchant/Circaso split is unchanged. Today's fee_bps
-- becomes one band for each direction, so no number changes until staff edit them; fee_bps stays, holding buying's
-- first band for readers that know one rate. Never edit this script once applied.

-- JSON lists written by the ramp: [{"fromMinor":"0","toMinor":"100000","feeBps":200}, ...]; '[]' is one band at fee_bps.
ALTER TABLE ramp.settings
    ADD COLUMN onramp_fee_bands  text NOT NULL DEFAULT '[]',
    ADD COLUMN offramp_fee_bands text NOT NULL DEFAULT '[]';

UPDATE ramp.settings
   SET onramp_fee_bands  = '[{"fromMinor":"0","toMinor":null,"feeBps":' || fee_bps || '}]',
       offramp_fee_bands = '[{"fromMinor":"0","toMinor":null,"feeBps":' || fee_bps || '}]';

-- Which band priced each quote and order: where it starts, in KES minor units (NULL for one flat band and on older
-- rows). The band's rate is already fee_bps on the row.
ALTER TABLE ramp.quotes ADD COLUMN fee_band_from_minor bigint;
ALTER TABLE ramp.orders ADD COLUMN fee_band_from_minor bigint;
