-- The participant's video track carries their screen, not their camera (CL8, 2026-11-09): the others show it whole
-- and say "<name> is sharing their screen". Reset on every (re)join and when they leave.
ALTER TABLE calls.participants
    ADD COLUMN IF NOT EXISTS screen boolean NOT NULL DEFAULT false;
