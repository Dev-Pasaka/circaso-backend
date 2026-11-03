-- Moments (Spaces phase 1; the Moments API contract of 2026-11-03): WhatsApp-style statuses that live 24 hours, end to
-- end encrypted on the poster's phone. The server keeps ciphertext, who each moment was shown to (with their copy of
-- the poster's sender key), view receipts and expiry, and the encrypted files in object storage. Nothing readable.
-- Never edit this script once applied; a change is a new script.

CREATE TABLE status.moments (
    moment_id        uuid        PRIMARY KEY,           -- UUIDv7, made on the server: ST4 pages by it
    poster_id        uuid        NOT NULL,
    client_moment_id uuid        NOT NULL,              -- made on the phone; a retried ST3 returns the first answer
    ciphertext       text        NOT NULL,              -- base64, at most 64 KiB decoded
    media_id         uuid,                              -- the one file it carries, in status.media
    posted_at        timestamptz NOT NULL,
    expires_at       timestamptz NOT NULL               -- posted_at + 24 hours
);
-- The claim behind clientMomentId: ST3 inserts with ON CONFLICT DO NOTHING and reads the winner back.
CREATE UNIQUE INDEX moments_poster_client_key ON status.moments (poster_id, client_moment_id);
-- ST4's own moments and the 50-a-day count.
CREATE INDEX moments_poster_idx ON status.moments (poster_id, moment_id);
-- status.purge-expired.
CREATE INDEX moments_expires_at_idx ON status.moments (expires_at);

CREATE TABLE status.audience (
    moment_id uuid        NOT NULL REFERENCES status.moments ON DELETE CASCADE,
    user_id   uuid        NOT NULL,
    key_copy  text,                                     -- the poster's sender key for this person, pairwise encrypted
    viewed_at timestamptz,                              -- the first ST5
    reacted   boolean     NOT NULL DEFAULT false,
    PRIMARY KEY (moment_id, user_id)
);
-- ST4: the moments shown to one person, by moment id.
CREATE INDEX audience_user_idx ON status.audience (user_id, moment_id);

CREATE TABLE status.media (
    media_id    uuid        PRIMARY KEY,                -- UUIDv7, made on the server
    poster_id   uuid        NOT NULL,
    kind        text        NOT NULL CHECK (kind IN ('IMAGE', 'VIDEO', 'VOICE')),
    size_bytes  bigint      NOT NULL CHECK (size_bytes > 0),
    state       text        NOT NULL CHECK (state IN ('PENDING', 'READY')),
    moment_id   uuid,                                   -- set by ST3; no foreign key, the file outlives a deleted
                                                        -- moment until the sweep has removed it from storage
    created_at  timestamptz NOT NULL,
    expires_at  timestamptz NOT NULL                    -- ST1 + 1 hour; then the moment's expiry; now once deleted
);
CREATE INDEX media_expires_at_idx ON status.media (expires_at);
CREATE INDEX media_moment_idx ON status.media (moment_id);
CREATE INDEX media_poster_idx ON status.media (poster_id);

-- The frames status sent, kept 7 days for replay (daily partitions made and dropped by status.purge-expired).
CREATE TABLE status.realtime (
    user_id  uuid        NOT NULL,
    seq      bigint      NOT NULL,
    frame    text        NOT NULL,
    sent_at  timestamptz NOT NULL,
    PRIMARY KEY (user_id, seq, sent_at)
) PARTITION BY RANGE (sent_at);
