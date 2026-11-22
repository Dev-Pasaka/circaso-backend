-- The hourly limit on M-Pesa prompts per user becomes optional (the owner, 2026-11-22: it frustrated a buyer whose
-- attempts had sent nothing). 0 means no limit, which is the new default; staff can still set one in the admin if a
-- merchant's account at the provider needs protecting. Only prompts that reached the phone are counted now.
ALTER TABLE ramp.settings DROP CONSTRAINT IF EXISTS settings_prompts_per_hour_check;
ALTER TABLE ramp.settings ADD CONSTRAINT settings_prompts_per_hour_check CHECK (prompts_per_hour >= 0);
UPDATE ramp.settings SET prompts_per_hour = 0, version = version + 1;
