-- The signed Kyber (ML-KEM 1024) prekey next to the signed Curve25519 prekey: libsignal 0.106 opens sessions with
-- PQXDH only, so a bundle without one cannot be used by any phone (decision 2026-10-28). Bundles published before
-- this column existed are useless to the app for the same reason, so they go; the phones publish again on their next
-- start (dev data only: production is not live).
DELETE FROM messaging.one_time_prekeys;
DELETE FROM messaging.key_bundles;

ALTER TABLE messaging.key_bundles
    ADD COLUMN kyber_pre_key_id        int  NOT NULL,
    ADD COLUMN kyber_pre_key           text NOT NULL,
    ADD COLUMN kyber_pre_key_signature text NOT NULL;
