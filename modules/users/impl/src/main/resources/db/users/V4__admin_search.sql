-- Staff search on the ops gateway (decision 2026-11-08): ProfileAdmin.searchByHandle and searchByName. Each index is
-- on the search key in the C collation (byte order), where a prefix is one contiguous range of the index, so one
-- index serves the prefix filter, the keyset comparison and the ORDER BY together (a text_pattern_ops index serves a
-- LIKE prefix but not `>` or ORDER BY under the database's own collation). Plain CREATE INDEX: Flyway runs each script
-- in a transaction, and the table is small at this stage. Never edit this script once applied; a change is a new one.

-- searchByHandle: (handle COLLATE "C") >= :prefix AND < :prefix || U+10FFFF AND LIKE, keyset on the handle.
CREATE INDEX IF NOT EXISTS profiles_handle_prefix_idx ON users.profiles ((handle COLLATE "C"));

-- searchByName: the same on lower(display_name), keyset on (lower(display_name), user_id) since names repeat.
CREATE INDEX IF NOT EXISTS profiles_name_prefix_idx ON users.profiles ((lower(display_name) COLLATE "C"), user_id);
