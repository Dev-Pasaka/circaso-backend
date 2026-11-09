-- When a joined participant's phone said its connection to the SFU is up (CL3b, 2026-11-09). Cloudflare's order is
-- "publish, wait for the connection to be connected, then make the publication discoverable": a pull of tracks whose
-- publisher isn't connected yet is held by the SFU for about 9 seconds. Phones pull a person once they are connected.
-- NULL while connecting, and again after every rejoin (a new session).
ALTER TABLE calls.participants
    ADD COLUMN IF NOT EXISTS connected_at timestamptz;
