-- The verified badge (2026-11-24): when the person's identity was verified with Didit's live environment, as ramp
-- reports it (ramp.UserVerificationChanged). verified_at is set while verification = 'VERIFIED' and null otherwise;
-- verification_changed_at is the changedAt of the last ramp event applied, so an older event that arrives late never
-- overwrites a newer one. Nullable columns with no default: nothing is rewritten. Never edit this script once applied.
ALTER TABLE identity.accounts
    ADD COLUMN verified_at             timestamptz,
    ADD COLUMN verification_changed_at timestamptz;
