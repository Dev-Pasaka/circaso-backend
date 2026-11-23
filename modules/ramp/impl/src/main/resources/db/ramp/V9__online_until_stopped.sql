-- A merchant can go online until they stop it themselves, with no end time (decision 2026-11-23). Such a session has
-- `online_open_ended` and no `online_until`; a timed one is as before. The unlocked keys still live only in the app
-- server's memory, so a restart ends either kind: `ramp.end-sessions` then ends the session in the database too and
-- records why (`offline_reason` RESTART), for the portal to say. `online_since` names the session, so that sweep
-- never ends a newer one opened in the meantime.
ALTER TABLE ramp.merchants
    ADD COLUMN online_open_ended boolean NOT NULL DEFAULT false,
    ADD COLUMN online_since      timestamptz,       -- when the current session began; NULL when offline
    ADD COLUMN offline_reason    text,              -- STOPPED | SIGNED_OUT | SUSPENDED | PROVIDER_BALANCE | WINDOW_ENDED | RESTART
    ADD COLUMN offline_at        timestamptz,
    ADD CONSTRAINT merchants_online_window_check CHECK (NOT (online_open_ended AND online_until IS NOT NULL));

-- The sessions `ramp.end-sessions` looks at each minute: every merchant with one open, timed or not.
CREATE INDEX merchants_sessions_idx ON ramp.merchants (merchant_id)
    WHERE online_open_ended OR online_until IS NOT NULL;
