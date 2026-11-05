-- Spaces (phase 3, Spaces core; the Spaces API contract of 2026-11-04): communities and small businesses with a space
-- chat that is NOT end-to-end encrypted (plan §5b), three join types, roles, moderation, reports and paid
-- memberships. The space's wallet is the wallet module's (owner type SPACE). Never edit this script once applied; a
-- change is a new script.

CREATE TABLE spaces.spaces (
    space_id          uuid        PRIMARY KEY,           -- UUIDv7
    handle            text        NOT NULL,              -- lowercase, its own namespace (not people's handles)
    name              text        NOT NULL,
    about             text        NOT NULL DEFAULT '',
    topic             text        NOT NULL CHECK (topic IN ('BUSINESS', 'SHOPPING', 'FARMING', 'HEALTH', 'FAITH',
                                                            'SPORT', 'BOOKS', 'CHAMAS', 'MUSIC', 'OTHER')),
    photo_media_id    uuid,
    cover_media_id    uuid,
    visibility        text        NOT NULL CHECK (visibility IN ('PUBLIC', 'MEMBERS_ONLY', 'INVITE_ONLY')),
    price_minor       bigint      CHECK (price_minor IS NULL OR price_minor > 0),   -- USDC minor units
    price_period      text        CHECK (price_period IS NULL OR price_period IN ('ONE_TIME', 'MONTHLY', 'YEARLY')),
    posting_mode      text        NOT NULL CHECK (posting_mode IN ('EVERYONE', 'ADMINS_ONLY')),
    private_messages  text        NOT NULL CHECK (private_messages IN ('ANYONE', 'MEMBERS')),
    state             text        NOT NULL CHECK (state IN ('SETTING_UP', 'LIVE', 'DELETED', 'REMOVED')),
    owner_id          uuid        NOT NULL,
    country           text,                              -- CF-IPCountry at creation, for "popular in Kenya"
    member_count      int         NOT NULL DEFAULT 0,
    last_post_at      timestamptz,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    version           bigint      NOT NULL DEFAULT 1,    -- optimistic lock for SP5
    create_key        text,                              -- SP2's Idempotency-Key
    removed_reason    text,                              -- why Circaso removed it (SPO2)
    search            tsvector    GENERATED ALWAYS AS (
                          to_tsvector('simple', name || ' ' || handle || ' ' || about || ' ' || lower(topic))
                      ) STORED,
    CHECK ((visibility = 'MEMBERS_ONLY') = (price_minor IS NOT NULL AND price_period IS NOT NULL))
);
-- One live handle per space; a deleted space gives its handle up (a removed one keeps it, so nobody poses as it).
CREATE UNIQUE INDEX spaces_handle_key ON spaces.spaces (handle) WHERE state <> 'DELETED';
CREATE UNIQUE INDEX spaces_create_key ON spaces.spaces (owner_id, create_key) WHERE create_key IS NOT NULL;
CREATE INDEX spaces_owner_idx ON spaces.spaces (owner_id, created_at);
CREATE INDEX spaces_search_idx ON spaces.spaces USING gin (search);
-- SP8's popular lists.
CREATE INDEX spaces_popular_idx ON spaces.spaces (country, member_count DESC)
    WHERE state = 'LIVE' AND visibility <> 'INVITE_ONLY';
CREATE INDEX spaces_handle_prefix_idx ON spaces.spaces (handle text_pattern_ops)
    WHERE state = 'LIVE' AND visibility <> 'INVITE_ONLY';

CREATE TABLE spaces.members (
    space_id          uuid        NOT NULL REFERENCES spaces.spaces ON DELETE CASCADE,
    user_id           uuid        NOT NULL,
    role              text        NOT NULL CHECK (role IN ('OWNER', 'ADMIN', 'MODERATOR', 'MEMBER')),
    joined_at         timestamptz NOT NULL,
    invited_by        uuid,
    notifications     text        NOT NULL DEFAULT 'ALL' CHECK (notifications IN ('ALL', 'MENTIONS', 'OFF')),
    muted_until       timestamptz,
    paid_state        text        CHECK (paid_state IS NULL OR paid_state IN ('ACTIVE', 'GRACE', 'ENDED')),
    period            text        CHECK (period IS NULL OR period IN ('ONE_TIME', 'MONTHLY', 'YEARLY')),
    paid_until        timestamptz,                       -- null for one-time and free memberships
    paid_amount_minor bigint,
    complimentary     boolean     NOT NULL DEFAULT false,
    reminded_for      timestamptz,                       -- the paid_until a renewal reminder was sent for
    read_up_to        uuid,                              -- the latest post read (post ids are UUIDv7)
    PRIMARY KEY (space_id, user_id)
);
CREATE INDEX members_user_idx ON spaces.members (user_id);
CREATE UNIQUE INDEX members_owner_key ON spaces.members (space_id) WHERE role = 'OWNER';
CREATE INDEX members_paid_idx ON spaces.members (paid_until) WHERE paid_state IN ('ACTIVE', 'GRACE');

CREATE TABLE spaces.bans (
    space_id   uuid        NOT NULL REFERENCES spaces.spaces ON DELETE CASCADE,
    user_id    uuid        NOT NULL,
    banned_by  uuid        NOT NULL,
    banned_at  timestamptz NOT NULL,
    PRIMARY KEY (space_id, user_id)
);
CREATE INDEX bans_user_idx ON spaces.bans (user_id);

CREATE TABLE spaces.requests (
    space_id     uuid        NOT NULL REFERENCES spaces.spaces ON DELETE CASCADE,
    user_id      uuid        NOT NULL,
    state        text        NOT NULL CHECK (state IN ('PENDING', 'ACCEPTED', 'DECLINED', 'CANCELLED')),
    requested_at timestamptz NOT NULL,
    answered_at  timestamptz,
    PRIMARY KEY (space_id, user_id)
);
CREATE INDEX requests_pending_idx ON spaces.requests (space_id, requested_at) WHERE state = 'PENDING';

CREATE TABLE spaces.invites (
    invite_id   uuid        PRIMARY KEY,
    space_id    uuid        NOT NULL REFERENCES spaces.spaces ON DELETE CASCADE,
    invitee_id  uuid        NOT NULL,
    inviter_id  uuid        NOT NULL,
    state       text        NOT NULL CHECK (state IN ('INVITED', 'ACCEPTED', 'DECLINED', 'CANCELLED')),
    invited_at  timestamptz NOT NULL,
    expires_at  timestamptz NOT NULL,                   -- invited_at + 14 days
    answered_at timestamptz
);
CREATE UNIQUE INDEX invites_space_invitee_key ON spaces.invites (space_id, invitee_id);
CREATE INDEX invites_invitee_idx ON spaces.invites (invitee_id, expires_at) WHERE state = 'INVITED';
CREATE INDEX invites_space_idx ON spaces.invites (space_id, invited_at);

-- SP13: what one membership payment must be; confirmed by the wallet's TransferConfirmed (spacePaymentId).
CREATE TABLE spaces.payments (
    payment_id      uuid        PRIMARY KEY,
    space_id        uuid        NOT NULL REFERENCES spaces.spaces ON DELETE CASCADE,
    payer_id        uuid        NOT NULL,
    kind            text        NOT NULL CHECK (kind IN ('JOIN', 'RENEWAL', 'REJOIN')),
    amount_minor    bigint      NOT NULL CHECK (amount_minor > 0),
    period          text        NOT NULL CHECK (period IN ('ONE_TIME', 'MONTHLY', 'YEARLY')),
    previous_minor  bigint,
    created_at      timestamptz NOT NULL,
    expires_at      timestamptz NOT NULL,
    confirmed_at    timestamptz
);
CREATE INDEX payments_payer_idx ON spaces.payments (payer_id, created_at);

CREATE TABLE spaces.media (
    media_id     uuid        PRIMARY KEY,
    owner_id     uuid        NOT NULL,
    purpose      text        NOT NULL CHECK (purpose IN ('SPACE_PHOTO', 'SPACE_COVER', 'POST')),
    kind         text        NOT NULL CHECK (kind IN ('IMAGE', 'VIDEO', 'VOICE')),
    content_type text        NOT NULL,
    size_bytes   bigint      NOT NULL CHECK (size_bytes > 0),
    width        int,
    height       int,
    duration_ms  bigint,
    state        text        NOT NULL CHECK (state IN ('PENDING', 'READY', 'USED')),
    created_at   timestamptz NOT NULL,
    expires_at   timestamptz NOT NULL                   -- upload window; far ahead once used
);
CREATE INDEX media_expires_idx ON spaces.media (expires_at) WHERE state <> 'USED';

CREATE TABLE spaces.posts (
    post_id        uuid        PRIMARY KEY,              -- UUIDv7: pages and read markers order by it
    space_id       uuid        NOT NULL REFERENCES spaces.spaces ON DELETE CASCADE,
    author_id      uuid        NOT NULL,
    client_post_id uuid        NOT NULL,                 -- the phone's; a retried SP29 returns the first answer
    text           text,
    reply_to       uuid,
    media_ids      text        NOT NULL DEFAULT '',      -- comma-separated media ids, in order
    created_at     timestamptz NOT NULL,
    removed_at     timestamptz,
    removed_by     text        CHECK (removed_by IS NULL OR removed_by IN ('AUTHOR', 'MODERATOR'))
);
CREATE UNIQUE INDEX posts_author_client_key ON spaces.posts (author_id, client_post_id);
CREATE INDEX posts_space_idx ON spaces.posts (space_id, post_id);
CREATE INDEX posts_author_idx ON spaces.posts (author_id, created_at);

CREATE TABLE spaces.reactions (
    post_id    uuid        NOT NULL REFERENCES spaces.posts ON DELETE CASCADE,
    user_id    uuid        NOT NULL,
    emoji      text        NOT NULL,
    reacted_at timestamptz NOT NULL,
    PRIMARY KEY (post_id, user_id)
);

CREATE TABLE spaces.reports (
    report_id    uuid        PRIMARY KEY,
    space_id     uuid        NOT NULL REFERENCES spaces.spaces ON DELETE CASCADE,
    post_id      uuid,                                   -- null: the whole space (Circaso only)
    reporter_id  uuid        NOT NULL,
    reason       text        NOT NULL CHECK (reason IN ('SPAM_SCAM', 'COUNTERFEIT', 'HARASSMENT', 'HATE_VIOLENCE',
                                                        'SEXUAL', 'OTHER')),
    detail       text,
    state        text        NOT NULL CHECK (state IN ('OPEN', 'DONE')),
    created_at   timestamptz NOT NULL,
    resolved_at  timestamptz,
    resolved_by  uuid,
    action       text        CHECK (action IS NULL OR action IN ('KEEP', 'REMOVE_POST', 'MUTE', 'REMOVE_MEMBER'))
);
-- One open report per person and target.
CREATE UNIQUE INDEX reports_open_key ON spaces.reports
    (reporter_id, space_id, coalesce(post_id, '00000000-0000-0000-0000-000000000000'::uuid)) WHERE state = 'OPEN';
CREATE INDEX reports_space_idx ON spaces.reports (space_id, state);
CREATE INDEX reports_reporter_idx ON spaces.reports (reporter_id, created_at);
CREATE INDEX reports_created_idx ON spaces.reports (created_at);

-- The frames spaces sent, kept 7 days for replay (daily partitions made and dropped by spaces.purge-expired).
CREATE TABLE spaces.realtime (
    user_id  uuid        NOT NULL,
    seq      bigint      NOT NULL,
    frame    text        NOT NULL,
    sent_at  timestamptz NOT NULL,
    PRIMARY KEY (user_id, seq, sent_at)
) PARTITION BY RANGE (sent_at);
