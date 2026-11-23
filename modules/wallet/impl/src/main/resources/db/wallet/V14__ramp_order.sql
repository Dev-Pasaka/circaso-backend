-- The off-ramp (decision 2026-11-23): a transfer may fund a ramp order. The wallet stores the id and echoes it on the
-- transfer's events; it is opaque here. One order is funded by one transfer at a time: a transfer that isn't FAILED
-- (an EXPIRED one may still land, WS4) holds the order, so a sale is never paid for twice.
ALTER TABLE wallet.transfers ADD COLUMN ramp_order_id uuid;
CREATE UNIQUE INDEX transfers_ramp_order_key ON wallet.transfers (ramp_order_id)
    WHERE ramp_order_id IS NOT NULL AND state <> 'FAILED';
