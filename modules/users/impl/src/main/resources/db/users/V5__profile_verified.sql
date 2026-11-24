-- The verified badge (2026-11-24): when the person's identity was verified with Didit's live environment, as identity
-- last reported it (identity.IdentityVerificationChanged); null when not verified. verified_changed_at is that event's
-- occurredAt, so a late, older event never overwrites a newer one. Nullable, no default: nothing is rewritten. Never
-- edit this script once applied.
ALTER TABLE users.profiles
    ADD COLUMN verified_at         timestamptz,
    ADD COLUMN verified_changed_at timestamptz;
