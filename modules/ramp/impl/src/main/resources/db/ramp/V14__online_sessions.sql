-- Merchants stay online across restarts and deploys (2026-11-24). While a merchant is online the app keeps an
-- encrypted working copy of their unlocked payment account in Redis (`rmp:online:{merchantId}`, AES-256-GCM under a key
-- derived from RAMP_CREDENTIALS_MASTER_KEY), and a restarted app restores it instead of ending the session with
-- RESTART. A timed window's copy lasts as long as the window; an open-ended session's at most this many days, after
-- which the next restart asks for the PIN again. Going offline deletes the copy.
ALTER TABLE ramp.settings
    ADD COLUMN online_session_max_days integer NOT NULL DEFAULT 30
        CHECK (online_session_max_days BETWEEN 1 AND 90);
