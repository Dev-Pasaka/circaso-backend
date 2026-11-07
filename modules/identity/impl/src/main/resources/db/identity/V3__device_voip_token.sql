-- The phone's Apple PushKit (VoIP) token (decision 2026-11-08), stored apart from push_token: an iPhone rings for an
-- incoming call through a VoIP push sent straight to APNs, which FCM can't deliver. Null on Android, on an iPhone
-- that hasn't sent one, and after sign-out, removal or APNs refusing it. Never edit this script once applied.
ALTER TABLE identity.devices
    ADD COLUMN IF NOT EXISTS voip_token text;
