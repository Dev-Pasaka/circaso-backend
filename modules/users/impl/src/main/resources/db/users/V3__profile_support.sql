-- Circaso's own support account (decision 2026-10-31): its profile is made from identity.SupportAccountCreated and
-- shows as verified support in the app. Every profile that exists is a person's, so the default fills them in.
ALTER TABLE users.profiles
    ADD COLUMN IF NOT EXISTS support boolean NOT NULL DEFAULT false;
