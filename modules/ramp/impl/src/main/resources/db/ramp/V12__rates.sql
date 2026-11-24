-- Admin-controlled rates (decision 2026-11-24, Pasaka). Until now both directions were quoted at the day's reference
-- rate (shillings per US dollar from open.er-api.com) with no spread, which goes stale intraday and sits below Kenya's
-- P2P USDC market. Each network now has pricing settings staff edit in the admin: a spread on each side of the
-- reference (buy = reference × (1 + buy spread), sell = reference × (1 − sell spread)), or exact manual rates with an
-- expiry. Spreads start at 0, so nothing changes until staff set them.
CREATE TABLE ramp.pricing_settings (
    network                 text        PRIMARY KEY,                     -- MAINNET | TESTNET
    mode                    text        NOT NULL DEFAULT 'REFERENCE_SPREAD'
        CHECK (mode IN ('REFERENCE_SPREAD', 'MANUAL')),
    buy_spread_bps          integer     NOT NULL DEFAULT 0 CHECK (buy_spread_bps BETWEEN 0 AND 2000),
    sell_spread_bps         integer     NOT NULL DEFAULT 0 CHECK (sell_spread_bps BETWEEN 0 AND 2000),
    manual_buy_rate         text,                                        -- KES per USDC, a decimal string
    manual_sell_rate        text,
    manual_expires_at       timestamptz,
    max_deviation_bps       integer     NOT NULL DEFAULT 500 CHECK (max_deviation_bps BETWEEN 1 AND 5000),
    max_reference_age_hours integer     NOT NULL DEFAULT 36 CHECK (max_reference_age_hours BETWEEN 1 AND 168),
    version                 bigint      NOT NULL DEFAULT 1,
    updated_by              text,
    reason                  text,
    updated_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pricing_settings_manual_check CHECK (
        mode <> 'MANUAL' OR (manual_buy_rate IS NOT NULL AND manual_sell_rate IS NOT NULL AND manual_expires_at IS NOT NULL)
    )
);

-- How each quote's and order's rate was made: its source, the reference rate read at the time (NULL when none could
-- be read: a manual rate quotes without one) and the margin over it in basis points (the spread set, or what a manual
-- rate works out to). All NULL on rows from before this script.
ALTER TABLE ramp.quotes
    ADD COLUMN rate_source    text,                 -- REFERENCE_SPREAD | MANUAL
    ADD COLUMN reference_rate text,
    ADD COLUMN spread_bps     integer;

ALTER TABLE ramp.orders
    ADD COLUMN rate_source    text,
    ADD COLUMN reference_rate text,
    ADD COLUMN spread_bps     integer;
