-- Asha's tokens per user, day and model, split as Anthropic prices them, for the staff cost report (decision
-- 2026-11-08). The day's activity (turns, voice seconds, web searches) stays one row a day in usage_daily; tokens move
-- here because one day can use more than one model. input_tokens is the fresh input only (neither read from nor
-- written to the prompt cache). Writers keep usage_daily's GREATEST upsert, per model.
CREATE TABLE assistant.usage_daily_tokens (
    user_id            uuid        NOT NULL,
    usage_day          date        NOT NULL,
    model              text        NOT NULL DEFAULT 'claude-haiku-5-5',
    input_tokens       bigint      NOT NULL,
    cache_read_tokens  bigint      NOT NULL DEFAULT 0,
    cache_write_tokens bigint      NOT NULL DEFAULT 0,
    output_tokens      bigint      NOT NULL,
    updated_at         timestamptz NOT NULL,
    PRIMARY KEY (user_id, usage_day, model)
);

-- The report reads a range of days across every user: both tables by day.
CREATE INDEX usage_daily_tokens_day_idx ON assistant.usage_daily_tokens (usage_day);
CREATE INDEX usage_daily_day_idx ON assistant.usage_daily (usage_day);

-- Days stored before this script: their tokens as Claude Haiku 5.5's, none from the cache (it wasn't counted apart).
INSERT INTO assistant.usage_daily_tokens (user_id, usage_day, model, input_tokens, cache_read_tokens,
                                          cache_write_tokens, output_tokens, updated_at)
SELECT user_id, usage_day, 'claude-haiku-5-5', input_tokens, 0, 0, output_tokens, updated_at
FROM assistant.usage_daily
WHERE input_tokens > 0 OR output_tokens > 0
ON CONFLICT DO NOTHING;
