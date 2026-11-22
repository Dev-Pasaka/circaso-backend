-- Email template ids (decision 2026-11-22): the environment holds keys and secrets only, so each email's template id
-- at the provider (Resend) is a row staff set in the admin. A template with no row isn't sent. The first time a
-- template has no row, the old INTEGRATIONS_EMAIL_TEMPLATES_<NAME> variable is copied in once, if it is still set.
-- Never edit this script once applied; a change is a new script.

CREATE TABLE ops.email_templates (
    template             text        PRIMARY KEY,       -- EmailTemplate's name (WELCOME, MERCHANT_SIGN_IN_CODE, ...)
    provider_template_id text        NOT NULL CHECK (provider_template_id <> ''),
    updated_by           text,                          -- the staff member's email; NULL for a row imported from the environment
    updated_at           timestamptz NOT NULL,
    version              bigint      NOT NULL DEFAULT 1 -- rises on each change: a stale save is refused
);
