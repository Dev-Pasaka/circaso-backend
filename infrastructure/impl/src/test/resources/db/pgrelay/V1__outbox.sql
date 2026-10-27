-- The shape every module's outbox has (identity.md → Data).
CREATE TABLE pgrelay.outbox (
    event_id     uuid        NOT NULL,
    occurred_at  timestamptz NOT NULL,
    type         text        NOT NULL,
    payload      text        NOT NULL,
    published_at timestamptz,
    PRIMARY KEY (event_id, occurred_at)
) PARTITION BY RANGE (occurred_at);
CREATE INDEX outbox_unpublished_idx ON pgrelay.outbox (occurred_at) WHERE published_at IS NULL;
