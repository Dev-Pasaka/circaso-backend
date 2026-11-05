-- Asha's web searches per user and day, for cost reports (decision 2026-11-05). Earlier days have none.
ALTER TABLE assistant.usage_daily ADD COLUMN web_searches int NOT NULL DEFAULT 0;
