-- 2026-11-22, after the first real test: PayHero refused a prompt because the merchant's prepaid service wallet was
-- empty, the buyer saw a generic failure and the same merchant kept being picked.

-- The least a merchant's service balance at the provider must hold for them to go online (KES minor units; 0 switches
-- the check off). A setting like the others: staff edit it in the admin.
ALTER TABLE ramp.settings
    ADD COLUMN provider_fee_floor_minor bigint NOT NULL DEFAULT 5000 CHECK (provider_fee_floor_minor >= 0);

-- What the merchant must fix at their provider before the engine routes to them again; NULL when nothing.
ALTER TABLE ramp.merchants
    ADD COLUMN payments_issue    text CHECK (payments_issue IN ('SERVICE_BALANCE')),
    ADD COLUMN payments_issue_at timestamptz;

-- When the provider refuses one merchant's prompt the next merchant is asked for the same quote, as a new order. A
-- quote therefore makes one order per attempt, no longer one order: a repeated request still finds the latest.
ALTER TABLE ramp.orders ADD COLUMN attempt integer NOT NULL DEFAULT 1 CHECK (attempt >= 1);
ALTER TABLE ramp.orders DROP CONSTRAINT IF EXISTS orders_quote_id_key;
CREATE UNIQUE INDEX orders_quote_attempt_key ON ramp.orders (quote_id, attempt);
