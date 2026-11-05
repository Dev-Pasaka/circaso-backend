-- Fees and limits (Spaces phase 2F; spaces-plan §7a and §7b, decided 2026-11-03). Rules live here, not in code:
-- edited with SQL now and from the admin site later. A change is a new version of a rule (same rule_id, version + 1,
-- its own effective_from); nothing is overwritten, so a payment can always be traced to the rule that priced it. The
-- version in force is a rule's highest version that has started, unless its effective_to has passed. Amounts in
-- dollars: every asset the wallet sends is a US dollar stablecoin. Never edit this script once applied.

-- The bounds a fee rate must stay within (one row). 0 to 200 basis points (0 % to 2 %) as decided; the bounds
-- themselves can be changed here, and fee_rules_in_bounds enforces them on every new rule version.
CREATE TABLE wallet.fee_bounds (
    id              smallint    PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    min_rate_bps    integer     NOT NULL CHECK (min_rate_bps >= 0),
    max_rate_bps    integer     NOT NULL CHECK (max_rate_bps <= 10000),
    updated_at      timestamptz NOT NULL,
    CHECK (min_rate_bps <= max_rate_bps)
);
INSERT INTO wallet.fee_bounds (id, min_rate_bps, max_rate_bps, updated_at) VALUES (1, 0, 200, now());

CREATE TABLE wallet.fee_rules (
    rule_id            uuid          NOT NULL,
    version            integer       NOT NULL CHECK (version > 0),
    -- What it matches; NULL matches anything. The most specific rule wins (wallet, chain, asset, owner types, kind).
    kind               text          CHECK (kind IN ('INTERNAL', 'EXTERNAL')),
    sender_type        text          CHECK (sender_type IN ('PERSON', 'GROUP', 'SPACE')),
    receiver_type      text          CHECK (receiver_type IN ('PERSON', 'GROUP', 'SPACE')),
    blockchain         text,
    asset              text,
    wallet_id          uuid,                                     -- one sending wallet's override
    -- What it charges: rate × min(amount, cap_base_usd), at least min_fee_usd and the gas floor below.
    rate_bps           integer       NOT NULL CHECK (rate_bps BETWEEN 0 AND 10000),
    cap_base_usd       numeric(20,6) NOT NULL DEFAULT 10000 CHECK (cap_base_usd > 0),
    min_fee_usd        numeric(20,6) CHECK (min_fee_usd >= 0),
    -- The 25 % rule: the network cost (estimated, plus the margin) may be at most this share of the fee.
    gas_share_max_bps  integer       NOT NULL DEFAULT 2500 CHECK (gas_share_max_bps BETWEEN 1 AND 10000),
    gas_margin_bps     integer       NOT NULL DEFAULT 2000 CHECK (gas_margin_bps BETWEEN 0 AND 10000),
    effective_from     timestamptz   NOT NULL,
    effective_to       timestamptz,
    note               text,
    created_at         timestamptz   NOT NULL,
    PRIMARY KEY (rule_id, version),
    CHECK (effective_to IS NULL OR effective_to > effective_from),
    CHECK (kind IS DISTINCT FROM 'EXTERNAL' OR receiver_type IS NULL)
);

CREATE FUNCTION wallet.fee_rules_in_bounds() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    bounds wallet.fee_bounds%ROWTYPE;
BEGIN
    SELECT * INTO bounds FROM wallet.fee_bounds WHERE id = 1;
    IF NEW.rate_bps < bounds.min_rate_bps OR NEW.rate_bps > bounds.max_rate_bps THEN
        RAISE EXCEPTION 'fee rate % bps is outside the bounds % to % bps', NEW.rate_bps,
            bounds.min_rate_bps, bounds.max_rate_bps USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER fee_rules_in_bounds BEFORE INSERT OR UPDATE ON wallet.fee_rules
    FOR EACH ROW EXECUTE FUNCTION wallet.fee_rules_in_bounds();

-- Seeds (§8 Q12, decided 2026-11-03): between Circaso people 0.5 %; to or from a group or a space 1 %; to an outside
-- address 2 %; Solana 0 % until a second spike (one challenge there can't carry the fee leg). All editable, to 0 %.
INSERT INTO wallet.fee_rules (rule_id, version, kind, sender_type, receiver_type, blockchain, rate_bps, effective_from, note, created_at) VALUES
    ('0195f2f0-0000-7000-8000-000000000001', 1, 'INTERNAL', NULL,    NULL,    NULL,         50,  now(), 'Between Circaso wallets', now()),
    ('0195f2f0-0000-7000-8000-000000000002', 1, 'INTERNAL', 'GROUP', NULL,    NULL,         100, now(), 'From a group wallet', now()),
    ('0195f2f0-0000-7000-8000-000000000003', 1, 'INTERNAL', NULL,    'GROUP', NULL,         100, now(), 'To a group wallet', now()),
    ('0195f2f0-0000-7000-8000-000000000004', 1, 'INTERNAL', 'SPACE', NULL,    NULL,         100, now(), 'From a space wallet', now()),
    ('0195f2f0-0000-7000-8000-000000000005', 1, 'INTERNAL', NULL,    'SPACE', NULL,         100, now(), 'To a space wallet', now()),
    ('0195f2f0-0000-7000-8000-000000000006', 1, 'EXTERNAL', NULL,    NULL,    NULL,         200, now(), 'To an address outside Circaso', now()),
    ('0195f2f0-0000-7000-8000-000000000007', 1, NULL,       NULL,    NULL,    'SOL',        0,   now(), 'Solana: no fee leg yet', now()),
    ('0195f2f0-0000-7000-8000-000000000008', 1, NULL,       NULL,    NULL,    'SOL-DEVNET', 0,   now(), 'Solana: no fee leg yet', now());

CREATE TABLE wallet.limit_rules (
    rule_id               uuid          NOT NULL,
    version               integer       NOT NULL CHECK (version > 0),
    owner_type            text          CHECK (owner_type IN ('PERSON', 'GROUP', 'SPACE')),
    wallet_id             uuid,                                  -- one wallet's override (a group's raised limit)
    asset                 text,
    per_payment_max_usd   numeric(20,6) CHECK (per_payment_max_usd > 0),   -- NULL: no cap on one payment
    daily_max_usd         numeric(20,6) CHECK (daily_max_usd > 0),         -- NULL: no cap on the window
    window_hours          integer       NOT NULL DEFAULT 24 CHECK (window_hours > 0),
    effective_from        timestamptz   NOT NULL,
    effective_to          timestamptz,
    note                  text,
    created_at            timestamptz   NOT NULL,
    PRIMARY KEY (rule_id, version),
    CHECK (effective_to IS NULL OR effective_to > effective_from)
);

-- Seeds (§7a): people keep the placeholders the server had (500 a payment, 1 000 a day); groups 10 000 USDC in any
-- rolling 24 hours, payouts to members included; spaces no limit (a business's own wallet), as a rule so one can be
-- set later without code.
INSERT INTO wallet.limit_rules (rule_id, version, owner_type, per_payment_max_usd, daily_max_usd, effective_from, note, created_at) VALUES
    ('0195f2f0-0000-7000-8000-000000000101', 1, 'PERSON', 500,  1000,  now(), 'Personal placeholders until KYC tiers', now()),
    ('0195f2f0-0000-7000-8000-000000000102', 1, 'GROUP',  NULL, 10000, now(), 'Group daily cap', now()),
    ('0195f2f0-0000-7000-8000-000000000103', 1, 'SPACE',  NULL, NULL,  now(), 'Spaces: no limit', now());

-- What W17 showed: a fee held for the signing window, for exactly one payment. Purged a day after it runs out.
CREATE TABLE wallet.fee_quotes (
    quote_id          uuid          PRIMARY KEY,
    holder_id         uuid          NOT NULL,
    signer_id         uuid          NOT NULL,
    blockchain        text          NOT NULL,
    payee_key         text          NOT NULL,                    -- user:<id> | group:<chatId> | address:<address>
    amount_minor      bigint        NOT NULL CHECK (amount_minor > 0),
    asset             text          NOT NULL,
    fee_minor         bigint        NOT NULL CHECK (fee_minor >= 0),
    rate_bps          integer       NOT NULL,
    minimum           boolean       NOT NULL,
    rule_id           uuid,
    rule_version      integer,
    gas_minor         bigint,                                    -- estimated network cost, micro-dollars
    gas_share_max_bps integer       NOT NULL,
    kind              text          NOT NULL,
    sender_type       text          NOT NULL,
    receiver_type     text,
    fee_address       text,
    no_fee_reason     text,
    created_at        timestamptz   NOT NULL,
    expires_at        timestamptz   NOT NULL
);
CREATE INDEX fee_quotes_expires_idx ON wallet.fee_quotes (expires_at);

-- The fee each transfer carries, locked when W4 prepared it; the payee gets exactly amount_minor, the sender pays both.
ALTER TABLE wallet.transfers
    ADD COLUMN fee_minor   bigint NOT NULL DEFAULT 0 CHECK (fee_minor >= 0),
    ADD COLUMN fee_address text;

-- One row per transfer: what was quoted and charged, under which rule, the network cost estimated and, once
-- confirmed, what the chain charged; for the reports per day, chain, kind and rule, and the 25 % alert.
CREATE TABLE wallet.fee_ledger (
    transfer_id        uuid          PRIMARY KEY,
    holder_id          uuid          NOT NULL,
    blockchain         text          NOT NULL,
    asset              text          NOT NULL,
    amount_minor       bigint        NOT NULL,
    fee_minor          bigint        NOT NULL CHECK (fee_minor >= 0),
    rate_bps           integer       NOT NULL,
    minimum            boolean       NOT NULL,
    rule_id            uuid,
    rule_version       integer,
    kind               text          NOT NULL,
    sender_type        text          NOT NULL,
    receiver_type      text,
    fee_address        text,
    no_fee_reason      text,
    gas_estimate_minor bigint,
    gas_share_max_bps  integer       NOT NULL,
    state              text          NOT NULL,                   -- QUOTED | COLLECTED | VOID
    tx_hash            text,
    actual_gas_native  text,
    actual_gas_minor   bigint,
    created_at         timestamptz   NOT NULL,
    updated_at         timestamptz   NOT NULL
);
CREATE INDEX fee_ledger_report_idx ON wallet.fee_ledger (created_at, blockchain);
