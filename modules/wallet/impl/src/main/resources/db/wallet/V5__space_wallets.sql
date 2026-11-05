-- Space wallets (Spaces phase 3, decision 2026-11-04): a wallet may belong to a space, whose owner signs for it
-- (option A, as group wallets). The space is spaces' own id; one wallet per space. Transfers name the space on
-- either side and the membership payment (SP13) they pay, so the events need no lookup. Space payments are priced by
-- phase 2F's fee rules (V4): the receiver's or sender's owner type SPACE (seed 1 %). Never edit this script once
-- applied; a change is a new one.

ALTER TABLE wallet.wallet_owners
    ADD COLUMN space_id uuid;
ALTER TABLE wallet.wallet_owners
    ADD CONSTRAINT wallet_owners_space_check CHECK (owner_type <> 'SPACE' OR space_id IS NOT NULL);
CREATE UNIQUE INDEX wallet_owners_space_key ON wallet.wallet_owners (space_id) WHERE space_id IS NOT NULL;

ALTER TABLE wallet.transfers
    ADD COLUMN from_space_id    uuid,
    ADD COLUMN to_space_id      uuid,
    ADD COLUMN space_payment_id uuid;
CREATE INDEX transfers_space_payment_idx ON wallet.transfers (space_payment_id) WHERE space_payment_id IS NOT NULL;
