-- Staff reads on the ops gateway (decision 2026-11-08): each query the identity `AccountAdmin` contract runs is served
-- by an index, never a scan. Plain CREATE INDEX (not CONCURRENTLY: Flyway runs each script in a transaction); the
-- tables are small at this stage, so the short write lock while each builds is acceptable. Never edit this script
-- once applied; a change is a new one.

-- AccountAdmin.newest (keyset on (created_at, user_id) DESC) and signUps (a created_at range, grouped by UTC day).
CREATE INDEX IF NOT EXISTS accounts_newest_idx ON identity.accounts (created_at DESC, user_id DESC);

-- AccountAdmin.findByEmail: lower(email) = lower(:email), the same expression, so the index answers it.
CREATE INDEX IF NOT EXISTS provider_links_email_idx ON identity.provider_links (lower(email));

-- AccountAdmin.activeUsers: distinct accounts whose active phone was seen since a day, 7 or 30 days ago.
CREATE INDEX IF NOT EXISTS devices_seen_idx ON identity.devices (last_seen_at) WHERE status = 'ACTIVE';

-- AccountAdmin.sessions: an account's sessions newest first (the table's only index was expires_at, for the purge).
CREATE INDEX IF NOT EXISTS sessions_user_idx ON identity.sessions (user_id, created_at DESC);
