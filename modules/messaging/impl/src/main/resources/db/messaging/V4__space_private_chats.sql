-- Private chats with a space's owner (Spaces phase 3, decision 2026-11-04): an ordinary end-to-end encrypted direct
-- chat, marked with the space it came from, and separate from any other chat between the same two people (so a
-- shop's customers never land in the owner's family chats). One per pair and space; the pair's own direct chat keeps
-- its claim among the chats without a space. Never edit this script once applied; a change is a new one.

ALTER TABLE messaging.chats
    ADD COLUMN via_space_id uuid;

DROP INDEX messaging.chats_direct_pair_key;
CREATE UNIQUE INDEX chats_direct_pair_key ON messaging.chats (direct_low, direct_high)
    WHERE kind = 'DIRECT' AND via_space_id IS NULL;
CREATE UNIQUE INDEX chats_direct_via_key ON messaging.chats (direct_low, direct_high, via_space_id)
    WHERE kind = 'DIRECT' AND via_space_id IS NOT NULL;
