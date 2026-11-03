-- A moment reaction is any one emoji, not just a heart (Pasaka, 2026-11-03). `reaction` is the emoji, NULL for none;
-- `reacted` stays and is always written with it (reacted = reaction IS NOT NULL), so ST4's reactionCount and older
-- readers keep working. Every heart already given becomes the heart emoji.
ALTER TABLE status.audience
    ADD COLUMN IF NOT EXISTS reaction text;

UPDATE status.audience SET reaction = '❤️' WHERE reacted AND reaction IS NULL;
