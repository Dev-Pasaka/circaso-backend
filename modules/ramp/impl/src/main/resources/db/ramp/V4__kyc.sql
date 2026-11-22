-- Identity verification for the ramp (decision 2026-11-22): Didit. A user passes it once, before their first on- or
-- off-ramp; a merchant before Circaso can approve them as a partner. The ramp keeps what the provider decided and an
-- identity key, never a document, a name or a picture: the provider keeps the record.

-- How a network verifies people. Like every other ramp setting these are rows staff edit in the admin; only the
-- provider's keys are in the environment.
ALTER TABLE ramp.settings
    -- Who runs the checks (didit). One of the names the server offers; `log` on a developer's machine only.
    ADD COLUMN kyc_provider          text    NOT NULL DEFAULT 'didit',
    -- Which side of the provider this network's checks run in: its sandbox (simulated checks) or live (real people).
    ADD COLUMN kyc_environment       text    NOT NULL DEFAULT 'LIVE' CHECK (kyc_environment IN ('SANDBOX', 'LIVE')),
    -- The provider's workflow for each kind of subject; empty means that kind can't be verified here yet.
    ADD COLUMN kyc_workflow_user     text    NOT NULL DEFAULT '',
    ADD COLUMN kyc_workflow_merchant text    NOT NULL DEFAULT '',
    -- The merchant portal's origin: where the provider's hosted page sends a merchant back to.
    ADD COLUMN merchant_portal_url   text    NOT NULL DEFAULT 'https://merchants.circaso.com',
    -- How many checks one subject may start in a day (a declined or expired one may be tried again).
    ADD COLUMN kyc_attempts_per_day  integer NOT NULL DEFAULT 3 CHECK (kyc_attempts_per_day BETWEEN 1 AND 20),
    -- How long a started check may stay unfinished before it is marked expired (also the life of its link).
    ADD COLUMN kyc_pending_hours     integer NOT NULL DEFAULT 24 CHECK (kyc_pending_hours BETWEEN 1 AND 672);

-- The testnet verifies in the provider's sandbox, with the two workflows that exist there ("Basic KYC" for users and
-- "ID, selfie and AML" for merchants). Mainnet verifies live; its workflows aren't made yet, so they stay empty.
UPDATE ramp.settings
SET kyc_environment       = 'SANDBOX',
    kyc_workflow_user     = 'a3cb5782-d5c1-4c35-832d-ed373d80a0ea',
    kyc_workflow_merchant = 'dabdb52a-b4bc-435a-b2bf-a636bb17bad6'
WHERE network = 'TESTNET';

CREATE TABLE ramp.verifications (                        -- who passed KYC for the ramp; never a document
    subject_kind       text        NOT NULL CHECK (subject_kind IN ('USER', 'MERCHANT')),
    subject_id         uuid        NOT NULL,
    -- The provider environment the check ran in, not the network: a sandbox approval is simulated and must never
    -- count where real people are verified, whichever network asks. A network's settings name the environment it trusts.
    environment        text        NOT NULL CHECK (environment IN ('SANDBOX', 'LIVE')),
    status             text        NOT NULL
        CHECK (status IN ('PENDING', 'IN_REVIEW', 'VERIFIED', 'DECLINED', 'EXPIRED')),   -- no row at all is NONE
    reason             text,                             -- our own code for why (document, identity_in_use, …); never the provider's text
    provider           text        NOT NULL,             -- who holds the session (didit): its decision is asked there
    session_id         text,                             -- the provider's current session: a webhook finds the row by it
    session_token      text,                             -- what the provider's SDK starts with; kept only while the check is unfinished
    session_url        text,                             -- the provider's hosted page for the same session
    session_expires_at timestamptz,                      -- when that link stops working
    identity_key       text,                             -- HMAC of the approved document (live only); set with VERIFIED
    attempts           integer     NOT NULL DEFAULT 0,   -- sessions ever started
    attempt_day        date,                             -- the Nairobi day the count below is for
    attempts_on_day    integer     NOT NULL DEFAULT 0,   -- sessions started on that day: the daily cap
    started_at         timestamptz,                      -- when the current session was started
    checked_at         timestamptz NOT NULL,             -- when the provider was last asked about it: the sweep's order
    verified_at        timestamptz,
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,
    PRIMARY KEY (subject_kind, subject_id, environment)
);
CREATE UNIQUE INDEX verifications_session_key ON ramp.verifications (session_id) WHERE session_id IS NOT NULL;
-- One identity verifies one account of each kind: a second account presenting the same document is refused.
CREATE UNIQUE INDEX verifications_identity_key ON ramp.verifications (subject_kind, environment, identity_key)
    WHERE identity_key IS NOT NULL;
-- ramp.sweep-verifications: the unfinished ones, least recently checked first.
CREATE INDEX verifications_open_idx ON ramp.verifications (checked_at)
    WHERE status IN ('PENDING', 'IN_REVIEW');

CREATE TABLE ramp.blocks (                               -- who is barred from the ramp
    block_id       uuid        PRIMARY KEY,
    user_id        uuid,                                 -- exactly one of the two is set
    identity_key   text,                                 -- a KYC identity key (an HMAC), never a document number
    reason         text        NOT NULL CHECK (reason IN ('OPS', 'REVERSAL', 'IDENTITY')),
    note           text        NOT NULL DEFAULT '',      -- what staff wrote; never personal data
    source_user_id uuid,                                 -- on an identity row: the user whose block it came from
    created_by     text        NOT NULL,                 -- a staff id, or `system` when an identity's block reached a new account
    created_at     timestamptz NOT NULL,
    CONSTRAINT blocks_one_subject_check CHECK ((user_id IS NULL) <> (identity_key IS NULL))
);
CREATE UNIQUE INDEX blocks_user_key ON ramp.blocks (user_id) WHERE user_id IS NOT NULL;
CREATE UNIQUE INDEX blocks_identity_key ON ramp.blocks (identity_key) WHERE identity_key IS NOT NULL;
CREATE INDEX blocks_source_idx ON ramp.blocks (source_user_id) WHERE source_user_id IS NOT NULL;
-- The staff list: blocked users, newest first.
CREATE INDEX blocks_listed_idx ON ramp.blocks (created_at DESC, block_id DESC) WHERE user_id IS NOT NULL;
