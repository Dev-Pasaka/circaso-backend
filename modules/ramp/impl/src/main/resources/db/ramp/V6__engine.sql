-- The merchant recommendation engine (decision 2026-11-23): an order's timeline, what a merchant is and offers, the
-- engine's settings per network and direction, and the log of every routing.

-- Every move an order makes, written in the transaction that makes it (and at creation). `orders.updated_at` is
-- overwritten on each move, so this is the only place the time of each step is kept. Nothing is backfilled: an order
-- made before this script has no timeline, and the merchant statistics simply don't see it.
CREATE TABLE ramp.order_events (
    event_id    uuid        PRIMARY KEY,                -- UUIDv7
    order_id    uuid        NOT NULL,
    merchant_id uuid        NOT NULL,
    network     text        NOT NULL,
    direction   text        NOT NULL,                   -- ON_RAMP | OFF_RAMP
    state       text        NOT NULL,                   -- the state the order entered
    detail      text,                                   -- why, when the move carried a reason (the failure reason)
    at          timestamptz NOT NULL
);
CREATE INDEX order_events_order_idx ON ramp.order_events (order_id, at);                    -- one order's timeline
CREATE INDEX order_events_finished_idx ON ramp.order_events (merchant_id, network, direction, at DESC)
    WHERE state IN ('COMPLETED', 'FAILED', 'EXPIRED', 'CANCELLED', 'REFUNDED');             -- a merchant's last finished orders

-- What a merchant is and offers. The type is staff's call (it sets the default order limit); the limit is staff's
-- override of that default; the two directions are the merchant's own switches on the portal.
ALTER TABLE ramp.merchants
    ADD COLUMN merchant_type     text    NOT NULL DEFAULT 'INDIVIDUAL'
        CHECK (merchant_type IN ('INDIVIDUAL', 'BUSINESS')),
    ADD COLUMN order_limit_minor bigint  CHECK (order_limit_minor IS NULL OR order_limit_minor > 0),  -- KES minor units; NULL: the type's default
    ADD COLUMN offers_onramp     boolean NOT NULL DEFAULT true,
    ADD COLUMN offers_offramp    boolean NOT NULL DEFAULT false;

-- The engine's settings in force: one row per network and direction, a versioned JSON document (the typed, validated
-- model is `EngineSettings`). A document and not columns: the two dozen numbers are read and written whole, the routing
-- log names the version that made each decision so every version is kept whole, and a new weight is a field of the
-- document with a default, not a migration.
CREATE TABLE ramp.engine_settings (
    network    text        NOT NULL,
    direction  text        NOT NULL,
    version    bigint      NOT NULL,
    document   text        NOT NULL,
    updated_by text,
    reason     text,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (network, direction)
);

-- Every version there has been, the seed included: what a routing decision's `settings_version` points at.
CREATE TABLE ramp.engine_settings_history (
    network    text        NOT NULL,
    direction  text        NOT NULL,
    version    bigint      NOT NULL,
    document   text        NOT NULL,
    updated_by text,
    reason     text,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (network, direction, version)
);

-- One row per routing: who was looked at and why each was left out or how each scored, the order they were ranked in,
-- who was picked, and what happened to each attempt. Pruned by `ramp.prune-routing` after the settings' days.
CREATE TABLE ramp.routing_decisions (
    decision_id       uuid        PRIMARY KEY,          -- UUIDv7
    quote_id          uuid        NOT NULL,
    order_id          uuid,                             -- the order the routing ended with; NULL when nobody took it
    merchant_id       uuid,                             -- who ended up with the order; NULL when nobody did
    network           text        NOT NULL,
    direction         text        NOT NULL,
    fiat_amount_minor bigint      NOT NULL,
    need_minor        bigint      NOT NULL,             -- what the merchant's float had to cover (USDC on an on-ramp)
    settings_version  bigint      NOT NULL,
    mode              text        NOT NULL,             -- ROUND_ROBIN | SHADOW | ACTIVE
    outcome           text        NOT NULL,             -- PENDING | ROUTED | NO_CANDIDATE | EXHAUSTED | STOPPED
    candidates        text        NOT NULL,             -- JSON: every merchant looked at, its inputs, scores or exclusion
    ranked            text        NOT NULL,             -- JSON: the failover order used, and the engine's own when shadowed
    steps             text        NOT NULL,             -- JSON: each attempt and how it ended
    created_at        timestamptz NOT NULL
);
CREATE INDEX routing_decisions_time_idx ON ramp.routing_decisions (network, created_at DESC, decision_id DESC);
CREATE INDEX routing_decisions_merchant_idx ON ramp.routing_decisions (merchant_id, created_at DESC, decision_id DESC)
    WHERE merchant_id IS NOT NULL;
CREATE INDEX routing_decisions_quote_idx ON ramp.routing_decisions (quote_id);
CREATE INDEX routing_decisions_prune_idx ON ramp.routing_decisions (network, direction, created_at);
