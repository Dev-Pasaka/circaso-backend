-- Group wallets (Spaces phase 2, decision 2026-11-03: a wallet belongs to a person or a group; option A, one signer).
-- A wallet's id is the key its rows are filed under in every table here (`circle_users.user_id`, `wallets.user_id`,
-- `transactions.user_id`, `transfers.sender_id` / `recipient_id`): a person's own wallet keeps their user id, so the
-- rows written before this script stay as they are and need no owner row; a group wallet gets a fresh UUIDv7 and one
-- row below. Spaces (phase 3) add `SPACE` rows; the fee and limit rules of phase 2F match on `owner_type` (none ⇒
-- `PERSON`) and may name a `wallet_id` as an override. Never edit this script once applied; a change is a new one.

CREATE TABLE wallet.wallet_owners (
    wallet_id   uuid        PRIMARY KEY,                -- the holder id the wallet's rows are filed under
    owner_type  text        NOT NULL CHECK (owner_type IN ('GROUP', 'SPACE')),
    chat_id     uuid,                                   -- GROUP: messaging's group chat
    signer_id   uuid        NOT NULL,                   -- who set the PIN: the only person who can sign (option A)
    created_by  uuid        NOT NULL,
    created_at  timestamptz NOT NULL,
    CHECK (owner_type <> 'GROUP' OR chat_id IS NOT NULL)
);
CREATE UNIQUE INDEX wallet_owners_chat_key ON wallet.wallet_owners (chat_id) WHERE chat_id IS NOT NULL;
                                                        -- one wallet per group: W14's claim
CREATE INDEX wallet_owners_signer_idx ON wallet.wallet_owners (signer_id);   -- W16: the wallets you sign for

-- Who signed, which approved payout it pays, and the groups on either side, so the events and W5 need no lookup.
-- NULL on every transfer written before this script (all personal: the signer was the sender).
ALTER TABLE wallet.transfers
    ADD COLUMN signer_id         uuid,
    ADD COLUMN payout_request_id uuid,
    ADD COLUMN from_group_id     uuid,
    ADD COLUMN to_group_id       uuid;
-- W4 refuses a second payment for an approved payout while one can still be signed or has gone through
-- (`wallet.payout_in_progress`); a failed or expired one doesn't count, so the owner can pay the request again. Not a
-- unique index: Circle may still report an expired transfer as signed, and that update must always apply.
CREATE INDEX transfers_payout_idx ON wallet.transfers (payout_request_id) WHERE payout_request_id IS NOT NULL;
