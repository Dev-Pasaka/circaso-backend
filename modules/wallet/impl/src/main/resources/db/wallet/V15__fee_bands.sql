-- Amount bands on fee rules (decision 2026-11-24, Pasaka: "the band sets the rate"). A rule may carry an ordered list
-- of bands, each [from, to) in dollars with its own rate; the band a payment's dollar amount falls in sets the rate for
-- the whole amount. The network cost, the cap base and the minimum fee work as before. NULL is a flat rule: one band
-- at rate_bps, exactly today's pricing, so every existing rule keeps its numbers. When bands are set, rate_bps holds
-- the first band's rate for readers that know only one rate. Rule versions stay immutable. Never edit this script once
-- applied.

-- JSON list written only by the wallet: [{"fromUsdMicros":0,"toUsdMicros":1000000,"rateBps":200}, ...].
ALTER TABLE wallet.fee_rules ADD COLUMN bands text;

-- Every band's rate within the bounds too, not only rate_bps.
CREATE OR REPLACE FUNCTION wallet.fee_rules_in_bounds() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    bounds wallet.fee_bounds%ROWTYPE;
    band_rate integer;
BEGIN
    SELECT * INTO bounds FROM wallet.fee_bounds WHERE id = 1;
    IF NEW.rate_bps < bounds.min_rate_bps OR NEW.rate_bps > bounds.max_rate_bps THEN
        RAISE EXCEPTION 'fee rate % bps is outside the bounds % to % bps', NEW.rate_bps,
            bounds.min_rate_bps, bounds.max_rate_bps USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.bands IS NOT NULL THEN
        FOR band_rate IN SELECT (band->>'rateBps')::integer FROM jsonb_array_elements(NEW.bands::jsonb) AS band LOOP
            IF band_rate IS NULL OR band_rate < bounds.min_rate_bps OR band_rate > bounds.max_rate_bps THEN
                RAISE EXCEPTION 'fee band rate % bps is outside the bounds % to % bps', band_rate,
                    bounds.min_rate_bps, bounds.max_rate_bps USING ERRCODE = 'check_violation';
            END IF;
        END LOOP;
    END IF;
    RETURN NEW;
END $$;

-- Which band priced each quote and transfer: its lower end in dollars (NULL for a flat rule and on older rows). The
-- band's rate is already rate_bps.
ALTER TABLE wallet.fee_quotes ADD COLUMN band_from_usd numeric(20,6);
ALTER TABLE wallet.fee_ledger ADD COLUMN band_from_usd numeric(20,6);
