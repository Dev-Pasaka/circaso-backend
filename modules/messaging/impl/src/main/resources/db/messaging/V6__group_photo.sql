-- A group's photo is an ordinary attachment of the group (AT1-AT3) that M8 marks as the group's avatar
-- (chats.avatar_media_id, decision 2026-11-05). While it is the avatar its expires_at is NULL: no expiry, so
-- messaging.purge-attachments (expires_at <= now()) never picks it and AT3 keeps serving it. Replacing or clearing it
-- sets expires_at to 24 hours later, deleting the group to now(), and the purge then removes it as any other.
-- Never edit this script once applied; a change is a new one.
ALTER TABLE messaging.attachments ALTER COLUMN expires_at DROP NOT NULL;
-- Only a completed upload can be a group's photo, so only a READY row is ever kept without an expiry.
ALTER TABLE messaging.attachments
    ADD CONSTRAINT attachments_kept_only_ready CHECK (expires_at IS NOT NULL OR state = 'READY');
