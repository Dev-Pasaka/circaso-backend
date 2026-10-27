CREATE TABLE pgmigrate_a.things (
    id    uuid PRIMARY KEY,
    label text NOT NULL
);

CREATE TABLE pgmigrate_a.outbox (
    event_id     uuid        NOT NULL,
    occurred_at  timestamptz NOT NULL,
    type         text        NOT NULL,
    payload      text        NOT NULL,
    published_at timestamptz,
    PRIMARY KEY (event_id, occurred_at)
) PARTITION BY RANGE (occurred_at);
