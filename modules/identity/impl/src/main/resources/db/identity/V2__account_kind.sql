-- Whose account it is (decision 2026-10-31): a person's, or Circaso's own support account, which never signs in on
-- the app and never holds a wallet. Every account that exists is a person's, so the default fills them in. The kind
-- is written once, when the account is created.
ALTER TABLE identity.accounts
    ADD COLUMN IF NOT EXISTS kind text NOT NULL DEFAULT 'PERSON';

-- The ops list of support accounts, in user-id (creation) order; only the few support rows are indexed.
CREATE INDEX IF NOT EXISTS accounts_support_idx ON identity.accounts (user_id) WHERE kind = 'SUPPORT';
