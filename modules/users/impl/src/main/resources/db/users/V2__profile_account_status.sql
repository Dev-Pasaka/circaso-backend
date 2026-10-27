-- The account's status as identity last reported it (identity.AccountStatusChanged), so users can hide a profile whose
-- account is not active and restore it when it is again (users.md → Events). status_changed_at is the event's
-- occurredAt: an older event that arrives late never overwrites a newer one.
ALTER TABLE users.profiles
    ADD COLUMN account_status    text        NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN status_changed_at timestamptz;
