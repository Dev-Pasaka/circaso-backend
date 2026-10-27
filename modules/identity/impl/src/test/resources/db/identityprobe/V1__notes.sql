-- A test-only schema: proves a module's tests reach PostgreSQL through infrastructure's test fixtures.
CREATE TABLE identityprobe.notes (
    id   uuid PRIMARY KEY,
    body text NOT NULL
);
