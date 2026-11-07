-- Any coin a wallet holds can be paid (decision 2026-11-07): EURC, USDT, a chain's own coin (ETH, POL, SOL), a known
-- token (WETH, cbBTC). Amounts stay exact integers in the coin's minor units, but an 18-decimal coin passes a bigint at
-- about 9 whole coins, so they become numeric(78,0) (a uint256 has 78 digits). Each row also keeps its coin's
-- decimals, and a transfer its contract and the US dollar price it was priced at: fees and limits are in dollars, the
-- fee is charged in the coin at that price, and the limits count each payment at it.
--
-- Forward only, and every existing row stays valid: they are all USDC or USDT, 6 decimals at one dollar, so the
-- defaults are what they always were, and a transfer's dollar weight is its amount and fee. Never edit this script
-- once applied; a change is a new one.

-- Wider amounts. Widening bigint to numeric keeps every value exactly; the CHECKs still hold.
ALTER TABLE wallet.transfers
    ALTER COLUMN amount_minor TYPE numeric(78,0),
    ALTER COLUMN fee_minor    TYPE numeric(78,0);
ALTER TABLE wallet.transactions
    ALTER COLUMN amount_minor TYPE numeric(78,0);
ALTER TABLE wallet.fee_quotes
    ALTER COLUMN amount_minor TYPE numeric(78,0),
    ALTER COLUMN fee_minor    TYPE numeric(78,0);
ALTER TABLE wallet.fee_ledger
    ALTER COLUMN amount_minor TYPE numeric(78,0),
    ALTER COLUMN fee_minor    TYPE numeric(78,0);

-- The coin's decimals beside its symbol; 6 for every row so far (USDC, USDT).
ALTER TABLE wallet.transfers    ADD COLUMN asset_decimals smallint NOT NULL DEFAULT 6 CHECK (asset_decimals BETWEEN 0 AND 36);
ALTER TABLE wallet.transactions ADD COLUMN asset_decimals smallint NOT NULL DEFAULT 6 CHECK (asset_decimals BETWEEN 0 AND 36);
ALTER TABLE wallet.fee_quotes   ADD COLUMN asset_decimals smallint NOT NULL DEFAULT 6 CHECK (asset_decimals BETWEEN 0 AND 36);
ALTER TABLE wallet.fee_ledger   ADD COLUMN asset_decimals smallint NOT NULL DEFAULT 6 CHECK (asset_decimals BETWEEN 0 AND 36);

-- A transfer's coin and price. token_address: the contract on the chain, '' for the chain's own coin, NULL on rows
-- from before (the asset offered there under the symbol). usd_price: one whole coin in dollars, NULL before (1).
-- usd_total_minor: the amount and the fee in micro-dollars at that price, what the rolling limit counts.
ALTER TABLE wallet.transfers
    ADD COLUMN token_address   text,
    ADD COLUMN usd_price       numeric(38,18) CHECK (usd_price > 0),
    ADD COLUMN usd_total_minor bigint;
UPDATE wallet.transfers SET usd_total_minor = (amount_minor + fee_minor)::bigint WHERE usd_total_minor IS NULL;
ALTER TABLE wallet.transfers
    ALTER COLUMN usd_total_minor SET NOT NULL,
    ADD CONSTRAINT transfers_usd_total_check CHECK (usd_total_minor >= 0);

-- A held quote keeps the fee in the coin and the price it was converted at, so W4 charges exactly what was shown.
ALTER TABLE wallet.fee_quotes
    ADD COLUMN usd_price     numeric(38,18) NOT NULL DEFAULT 1 CHECK (usd_price > 0),
    ADD COLUMN fee_usd_minor bigint;
UPDATE wallet.fee_quotes SET fee_usd_minor = fee_minor::bigint WHERE fee_usd_minor IS NULL;
ALTER TABLE wallet.fee_quotes ALTER COLUMN fee_usd_minor SET NOT NULL;

-- The ledger reports fees in dollars whatever coin they were taken in (the 25 % gas alert compares dollars).
ALTER TABLE wallet.fee_ledger
    ADD COLUMN usd_price     numeric(38,18) NOT NULL DEFAULT 1 CHECK (usd_price > 0),
    ADD COLUMN fee_usd_minor bigint;
UPDATE wallet.fee_ledger SET fee_usd_minor = fee_minor::bigint WHERE fee_usd_minor IS NULL;
ALTER TABLE wallet.fee_ledger ALTER COLUMN fee_usd_minor SET NOT NULL;
