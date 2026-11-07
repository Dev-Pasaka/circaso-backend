-- Any coin a wallet holds can be paid (decision 2026-11-07), into a group's wallet too. The group keeps counting in
-- US dollars: amount_minor stays micro-dollars (USDC's minor units), a dollar stablecoin's own amount and any other
-- coin's value at the price it was paid at, so goals, totals and what the group holds read as before. The coin that
-- actually moved is kept beside it. Forward only: rows before have no paid_* (they were USDC or USDT, counted at one
-- dollar). Never edit this script once applied.

ALTER TABLE chama.contributions
    ADD COLUMN paid_asset        text,
    ADD COLUMN paid_decimals     smallint CHECK (paid_decimals BETWEEN 0 AND 36),
    ADD COLUMN paid_amount_minor numeric(78,0) CHECK (paid_amount_minor > 0);

ALTER TABLE chama.direct_payouts
    ADD COLUMN paid_asset        text,
    ADD COLUMN paid_decimals     smallint CHECK (paid_decimals BETWEEN 0 AND 36),
    ADD COLUMN paid_amount_minor numeric(78,0) CHECK (paid_amount_minor > 0);
