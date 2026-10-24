# Phone codes with Twilio Verify

Twilio Verify makes the one-time code, texts it, expires it and checks it, so the code never passes through our servers. Identity still decides **when** to send (our limits: 3 codes per number per 10 minutes, 10 per device per day) and still locks an account for 10 minutes after 5 wrong codes; Twilio's own limits apply on top.

Turn it on with `INTEGRATIONS_VERIFY_PROVIDER=twilio` and the settings below. With the default, `none`, identity makes the code itself and `INTEGRATIONS_SMS_PROVIDER` delivers it, as before.

## Set up in Twilio

1. Twilio Console → **Verify → Services → Create new**. Name it Circaso, enable the **SMS** channel, code length 6. Copy the **Service SID** (starts `VA`) into `TWILIO_VERIFY_SERVICE_SID`.
2. **Account SID** (`AC…`) and **Auth Token** are on the Console dashboard: `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`. Better: **Account → API keys & tokens → Create API key**, then put the key's `SK…` sid in `TWILIO_ACCOUNT_SID` and its secret in `TWILIO_AUTH_TOKEN`, so the main token never leaves Twilio and the key can be revoked alone.
3. Optional: `TWILIO_VERIFY_LOCALE` sets the language of the text (see the locales Twilio lists for Verify).
4. A **trial** Twilio account can only text numbers you have verified in the console. Upgrade the account before testing with other people.
5. In the Verify service settings, switch on **Fraud Guard** and check which countries are allowed (Kenya, and any other market you launch in), so a burst of codes to the wrong places is blocked.

The values go in `.env` locally and in `/opt/circaso/.env` on the dev server. Never in git or in GitHub.

## What the app sends

`POST /auth/phone/start` and `/auth/phone/verify` are unchanged for the phone app. The Android SMS Retriever hash (`identity.sms-retriever-hash`) is passed to Twilio as `AppHash`, so the text can be read without the SMS permission.

## Failure behaviour

- A number Twilio will not text (invalid, blocked country): the person sees the same "couldn't send the code" message as any sending failure.
- Twilio's own rate limit: the person is told to wait (`request.rate_limited`).
- A code Twilio no longer has (expired, already used, too many tries): `identity.code_expired`; the person starts again.
- Twilio down or our credentials wrong: "couldn't send the code" and an error in the log (credentials) or a warning (outage).
