-- The admin's "Verified" filter on the user list (2026-11-24): AccountAdmin.newest with verifiedOnly is the same keyset
-- on (created_at, user_id) DESC over verified accounts only. A partial index, so the filter never walks every account
-- to find the few verified ones. Plain CREATE INDEX (Flyway runs each script in a transaction; the table is small).
-- Never edit this script once applied; a change is a new one.
CREATE INDEX IF NOT EXISTS accounts_verified_idx ON identity.accounts (created_at DESC, user_id DESC)
    WHERE verification = 'VERIFIED';
