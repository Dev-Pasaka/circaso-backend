-- The group wallet's owner sends straight from it (decision 2026-11-06): no payout request, no approvals. Each such
-- payment is recorded once when the wallet confirms it, so what the group paid out and still holds stays true; a
-- payout for a request is counted on its request, as before. Never edit this script once applied.

CREATE TABLE chama.direct_payouts (
    transfer_id   uuid        PRIMARY KEY,               -- wallet's transfer, once
    chat_id       uuid        NOT NULL,                  -- messaging's group chat whose wallet sent it
    amount_minor  bigint      NOT NULL CHECK (amount_minor > 0),
    asset         text        NOT NULL,
    at            timestamptz NOT NULL
);
CREATE INDEX direct_payouts_chat_idx ON chama.direct_payouts (chat_id);
