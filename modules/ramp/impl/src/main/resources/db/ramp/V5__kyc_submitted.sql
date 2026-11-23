-- A check the person has finished and the provider hasn't decided yet is still PENDING: the provider is working on
-- it. `submitted` tells it from one the person left halfway, so the app and the portal say "we're checking" instead
-- of "finish verifying", and no link is handed out to reopen a capture that is done. It is the provider's own word
-- (Didit: the session's flow has finished), set and cleared only when the provider is read; false in every other status.
ALTER TABLE ramp.verifications
    ADD COLUMN submitted boolean NOT NULL DEFAULT false;
