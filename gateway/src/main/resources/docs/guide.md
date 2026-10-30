Circaso is a chat-first money app: messages, payments, group calls and chamas. This API is what the Circaso app talks to. This page explains how to use it, in the order you will meet things: **sign in, verify the phone, register the device, then everything else.** Every call below is also listed in the sidebar with its request, its response and the errors it can return.

## Quick start: from nothing to a signed-in app

Everyone starts the same way. Google or Apple proves who you are, an SMS code (or a carrier check) proves the phone number is yours, and a hardware-backed key proves which phone you are on. Only then does the account become active.

| Step | Call | What it does | You get back |
| --- | --- | --- | --- |
| 1 | `POST /v1/auth/nonce` | Asks for a one-time number to tie the Google or Apple request to this attempt. Valid 5 minutes, usable once. | `nonce` |
| 2 | `POST /v1/auth/social` | Sends the Google or Apple ID token with the nonce and this phone's details. | `next` and an **onboarding** token |
| 3a | `POST /v1/auth/phone/start`, then `POST /v1/auth/phone/verify` | Sends a 6-digit SMS code to the number, then checks it. | `next: REGISTER_DEVICE` |
| 3b | `POST /v1/auth/phone/carrier` | On Android, a one-tap carrier check instead of an SMS. | `next: REGISTER_DEVICE` |
| 4 | `POST /v1/devices` | Registers this phone's public key. The account turns **active** and the **app** session is issued. | app token, refresh token, agent token |
| 5 | any `app` route | Send `Authorization: Bearer <accessToken>`. | |
| 6 | `POST /v1/auth/refresh` | Before the 15-minute access token ends, trade the refresh token for a new pair. Signed with the device key. | new tokens |
| 7 | `POST /v1/auth/signout` | Ends the session. The phone stays registered, so signing back in is one tap. | |

Step 2 tells you what to do next in `next`:

| `next` | Meaning | Do this |
| --- | --- | --- |
| `VERIFY_PHONE` | A new account, or one that has not finished setting up. | Step 3 |
| `REVERIFY_PHONE` | A known account on a phone it does not know. | Step 3 with the account's own number, then step 4. The old phone is signed out. |
| `REGISTER_DEVICE` | The number is verified on this phone, but the phone has no key yet. | Step 4 |
| `DONE` | This phone is registered and you proved it with its key. | You already have an app session. |

A signed-in app then does the rest: set a name and an `@handle` (**Profile**, **Handles**), sync the phone book (**Contacts**), connect with people (**Connections and invites**), and ask Asha (**Asha chat**).

## Base URL, versions and headers

| | |
| --- | --- |
| Base URL | `https://api.circaso.com` (every route is under `/v1`) |
| Versions | `/v1` changes only by adding things. A breaking change gets `/v2` and `/v1` stays until old apps are forced to update. |
| `Authorization` | `Bearer <accessToken>` on every route that needs a token. |
| `X-Client-Version` | The app and its version, for example `android/1.4.0`. An app older than the minimum answers `426` with code `request.client_outdated`, and the app shows its update screen. |
| `Idempotency-Key` | A UUID on every `POST`, `PUT`, `PATCH` and `DELETE`. See below. |
| `X-Agent-Token` | Only when talking to Asha: the session's read-only agent token (it must be your own: another user's is refused with `auth.token_invalid`), so her tools can read your contacts. |

Times are ISO-8601 UTC (`2026-10-21T18:06:00Z`). Ids are UUIDs. Phone numbers are E.164 (`+254712345678`) and are never shown to other people: they see a name, an `@handle` and a picture. JSON is camelCase and unknown fields are ignored.

## Tokens and scopes

Every token is a JWT that lives 15 minutes. What a token can reach depends on its **scope**:

| Scope | You get it from | It can reach |
| --- | --- | --- |
| `onboarding` | Step 2, before the phone and device are verified | Phone verification, device registration and the account-recovery request. Nothing else. |
| `app` | Step 4 and every refresh | Everything for your own account. |
| `agent` | Issued together with the `app` token, shares its session, rotates with it | Only `GET` routes under `/v1/agent/`, for your own data. It is how Asha reads without ever being able to change anything. |
| `ops` | Never issued to the app | Internal support tools, and this documentation outside local runs. |

A route that needs a scope your token does not have answers `403` with `auth.scope_insufficient`. A missing or bad token answers `401` with `auth.token_missing` or `auth.token_invalid`. An ended session answers `401` with `identity.session_revoked`: sign in again.

## The response envelope

Every response, success or failure, has the same shape, and `httpStatusCode` always equals the real HTTP status:

```json
{
  "httpStatusCode": 200,
  "status": true,
  "message": "Success",
  "code": null,
  "details": null,
  "requestId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "data": { "nonce": "b8f3…", "expiresInSeconds": 300 }
}
```

```json
{
  "httpStatusCode": 409,
  "status": false,
  "message": "This number already has a Circaso account.",
  "code": "identity.phone_taken",
  "details": null,
  "requestId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "data": null
}
```

Key the app's text on `code`, never on `message`. `details` carries `retryAfterSeconds` (also sent as the `Retry-After` header) and, for a bad request, `fields`, a list of the fields that are wrong, each with what is wrong with it: `"fields": [{"field": "displayName", "problem": "required"}]`. Quote `requestId` to support: it finds the exact trace and logs. The only responses that are not the envelope are the health routes, the audio from *Turn a reply into audio* and the Asha event stream.

## Idempotency

Every state-changing request needs an `Idempotency-Key` header holding a fresh UUID. **Use the generator in the bar at the top of this page**: click *Copy* (or the key itself) and paste it into the header; it makes a new one straight away, so you never reuse a key by accident. In code, any UUID v4 will do (`UUID.randomUUID()`, `crypto.randomUUID()`). The first response is kept for 24 hours. If the network drops and the app sends the same request with the same key, it gets the first response again, marked `Idempotent-Replayed: true`, and nothing runs twice. If the first request is still running, the repeat answers `409` with `request.in_progress`. A request without the header answers `400`. Server errors (`5xx`) are not kept, so a retry runs again.

## Rate limits

When a limit is hit the answer is `429` with `request.rate_limited`, `details.retryAfterSeconds` and the `Retry-After` header.

| What | Limit |
| --- | --- |
| Nonce and social sign-in | 20 per IP per minute |
| SMS codes | 3 per number per 10 minutes, 10 per phone per day |
| Wrong codes | 5, then the account is locked for 10 minutes |
| Contact sync | 5 per user per hour |
| `@handle` lookups | 30 per user per hour |
| Key bundles (`GET /v1/keys/{userId}`) | 60 per user per hour, answered with `messaging.rate_limited` |
| Support recovery requests | 3 per number per day, 5 per IP per day |
| Phone release | 1 per number per 7 days |
| Asha's agent token (all `/v1/agent/` routes) | 60 per user per minute, in its own bucket |
| Everything else | 120 per user per minute |

## Lists and pages

Lists take `?cursor=<opaque>&limit=<n>` (default 50, most 200) and answer with `nextCursor`, which is `null` on the last page. Pass `nextCursor` back as `cursor` for the next page; never build one yourself.

## Errors you will meet

The shared ones, then each area's own. Codes are stable.

| Code | Status | When |
| --- | --- | --- |
| `request.malformed` | 400 | Bad JSON, a missing field or a missing `Idempotency-Key`; `details.fields` says which |
| `request.not_found` | 404 | An unknown route, or something that is not yours |
| `request.client_outdated` | 426 | `X-Client-Version` is below the minimum |
| `request.rate_limited` | 429 | Any rate limit |
| `request.in_progress` | 409 | The same `Idempotency-Key` is still running, or (I6) a second registration raced the first: keep the other response, don't retry |
| `auth.token_missing`, `auth.token_invalid` | 401 | No token, or a bad or expired one |
| `auth.scope_insufficient` | 403 | The token's scope cannot use this route |
| `service.unavailable` | 503 | The server is too busy to take the request right now (no database connection came free within 3 seconds); retry after a moment with the same `Idempotency-Key` |
| `internal.error` | 500 | Something unexpected; quote the `requestId` |
| `identity.nonce_invalid` | 400 | The nonce is missing, used or older than 5 minutes |
| `identity.provider_token_invalid` | 401 | Google or Apple did not accept the token |
| `identity.account_suspended`, `identity.account_blocked` | 403 | The account cannot sign in |
| `identity.account_deleted` | 404 | The account was deleted |
| `identity.recovery_pending` | 409 | A support recovery is running |
| `identity.phone_invalid` | 400 | Not an E.164 number |
| `identity.phone_taken` | 409 | The number belongs to another account |
| `identity.phone_mismatch` | 409 | A new phone is verifying a number that is not the account's |
| `identity.code_invalid` | 400 | Wrong code; the message says how many tries are left |
| `identity.code_expired` | 400 | The code is older than 5 minutes |
| `identity.code_locked` | 429 | Five wrong codes; wait 10 minutes |
| `identity.sms_unavailable` | 502 | The SMS provider is down; offer a retry |
| `identity.carrier_unsupported` | 400 | The carrier check was refused; fall back to SMS |
| `identity.device_attestation_failed` | 403 | Play Integrity or App Attest rejected the phone (production) |
| `identity.device_proof_invalid` | 401 | A bad device signature on refresh |
| `identity.session_revoked` | 401 | The session ended (replaced, signed out, suspended) |
| `identity.provider_last` | 409 | You cannot unlink your only sign-in |
| `identity.provider_in_use` | 409 | That Google or Apple login belongs to another account |
| `identity.recovery_not_eligible` | 403 | Support can only restore accounts that completed identity verification |
| `identity.release_not_allowed` | 403 | A verified account's number cannot be released |
| `users.profile_invalid` | 400 | Name empty or over 40 characters, about over 140 |
| `users.version_conflict` | 409 | The profile changed since you read it; reload and retry |
| `users.avatar_invalid` | 400 | Wrong type, over 5 MB or not uploaded |
| `users.handle_invalid` | 400 | Breaks the handle rules or is reserved |
| `users.handle_taken` | 409 | Taken, or on hold for its old owner |
| `users.handle_cooldown` | 409 | Changed less than 30 days ago |
| `users.handle_not_found` | 404 | No such handle, or its owner turned lookup off (the two look the same on purpose) |
| `users.not_reachable` | 403 | No connection, or blocked either way (it never says which) |
| `users.contact_not_found` | 404 | That person is not in your contacts |
| `users.invite_invalid` | 404 | The invite expired or was revoked, or its owner's account is gone |
| `users.sync_too_large` | 400 | More than 1,000 numbers in one batch |
| `users.cannot_block_self` | 400 | You cannot block yourself |
| `messaging.keys_not_found` | 404 | The person has not published encryption keys yet, or this phone has not published its own |
| `messaging.keys_proof_invalid` | 403 | A key upload without valid `X-Device-Timestamp` / `X-Device-Signature` headers, or one more than 5 minutes off the server clock |
| `messaging.too_many_prekeys` | 400 | More than 100 one-time prekeys in an upload, or kept after a top-up |
| `messaging.prekey_id_exists` | 409 | A top-up resends a one-time prekey id that is already stored |
| `messaging.rate_limited` | 429 | More than 60 key bundles in an hour; `retryAfterSeconds` says when to try again |
| `messaging.chat_not_found` | 404 | No such chat, or you're not in it (the two look the same on purpose); also a member id that isn't in the group |
| `messaging.not_admin` | 403 | Only an admin can do that (add when the group doesn't allow members to, change the group, remove, set roles), nobody removes the owner, and a direct chat has no admins |
| `messaging.group_full` | 409 | Adding would pass 1,000 people |
| `messaging.group_invalid` | 400 | The sealed name and avatar (`sealedMeta`) are missing, not base64, or over 4 KB |
| `messaging.version_conflict` | 409 | The group changed since you read its `version`: load it again and retry |
| `messaging.not_a_group` | 400 | Leaving a direct chat, or removing someone from one: only groups have members to remove |
| `messaging.message_too_large` | 400 | A ciphertext over 64 KB once decoded; send files as chat media |
| `messaging.recipients_mismatch` | 400 | A direct message whose `recipients` isn't exactly the other person, or a `SENDER_KEY` message to someone who isn't another member |
| `messaging.delete_window_passed` | 409 | Delete for everyone after 48 hours, or by someone other than the sender |
| `messaging.attachment_too_large` | 400 | An attachment's `sizeBytes` is under 1 byte or over 32 MiB |
| `messaging.attachment_not_found` | 404 | No such attachment, not complete yet, expired, or not yours to see (completing someone else's upload, or a chat you aren't in): all look the same |
| `messaging.attachment_incomplete` | 400 | Completing an upload that isn't in storage, or not at the size you said: upload again, then complete |
| `wallet.not_found` | 404 | You have no wallet yet: create one first (`POST /v1/wallet`) |
| `wallet.already_exists` | 409 | `POST /v1/wallet` when the wallet is already set up |
| `wallet.not_ready` | 409 | The wallet is still being set up (the PIN isn't set yet), so a chain can't be added or a payment sent |
| `wallet.not_on_chain` | 409 | A payment on a chain your wallet isn't on: add the chain first (`POST /v1/wallet/chains`) |
| `wallet.chain_unsupported` | 400 | A blockchain the server doesn't support (or, when adding one, a chain retired from receiving); `GET /v1/wallet/chains` lists those that are |
| `wallet.account_restricted` | 403 | A payment from an account that isn't active (suspended, blocked, being deleted) |
| `wallet.on_hold` | 403 | Payments are on hold for a while after a support restore |
| `wallet.amount_invalid` | 400 | An amount that can't be sent: zero, more decimals than the asset has, `amountMinor` and `amount` that disagree, or an asset not offered on that chain |
| `wallet.limit_exceeded` | 400 | Over the per-payment or daily limit |
| `wallet.insufficient_funds` | 400 | The wallet doesn't hold enough on the payment's chain |
| `wallet.self_transfer` | 400 | Paying yourself, by user or by your own address |
| `wallet.recipient_no_wallet` | 409 | The person hasn't set up a wallet: offer `POST /v1/wallet/recipients/{userId}/remind` |
| `wallet.recipient_not_on_chain` | 409 | The person can't receive on that chain; `GET /v1/wallet/recipients/{userId}` lists where they can |
| `wallet.address_invalid` | 400 | Not a valid address on the payment's chain |
| `wallet.pin_locked` | 400 | Circle locked PIN entry after wrong tries; try later |
| `wallet.transfer_not_found` | 404 | No such payment, or not yours |
| `wallet.funds_remaining` | 409 | Deleting the account while the wallet's US dollar stablecoins (USDC), added up over every chain, come to 0.01 or more, or it holds any amount of any other asset: send it out first |
| `wallet.custody_unavailable` | 502 | Circle, which keeps the wallets, is down or refused the call, or (on account deletion) a wallet sits on a chain no longer configured; try again |
| `assistant.quota_exceeded` | 429 | Today's Asha allowance is used up; `retryAfterSeconds` says when it resets |
| `assistant.voice_too_long` | 400 | A voice clip over 60 seconds (`X-Duration-Seconds` may be a decimal such as `5.5`; it is rounded up) |
| `assistant.turn_not_found` | 404 | The phone's answer arrived after the question expired (10 minutes) or was already answered |
| `assistant.model_unavailable` | 502 | Asha's model is down; try again |
| `assistant.language_not_supported` | 400 | Speech (A4) asked for a language the server voice doesn't speak, such as Swahili; the phone reads the reply aloud itself. On the live voice socket it is the close reason instead |

## Talking to Asha: streaming and the phone's help

`POST /v1/assistant/chat/messages` answers with **server-sent events** (`text/event-stream`), not the envelope, and streams the reply from the model as it is written, so the first words show within a second or two. Errors that happen before any text (a used-up allowance, a bad request, a token that isn't yours) are still the envelope.

| Event | Data | Meaning |
| --- | --- | --- |
| `message` | `{"delta": "…"}` | The next piece of the reply, as the model wrote it. Append the pieces. |
| `tool_request` | `{"turnId", "callId", "name", "arguments"}` | Asha needs something only the phone can do: search or read your chats, which stay end-to-end encrypted on the server. Run the tool locally and answer with `POST …/messages/{turnId}/tool-results`; its response is a new event stream that carries on the same reply. |
| `done` | `{"message": {…}}` | The reply is complete and saved. Its text is everything shown, including what Asha said before a tool request, with a blank line between the parts. |
| `error` | `{"code", "message"}` | The model failed after some text had gone out (the `200` was already sent); nothing is saved. Ask again. |

The phone sends back at most 30 short snippets. They are used for that answer and never stored; Asha keeps only a note of which chats and how many messages she read, and the answer says so ("Asha read 4 messages from Family").

## Talking to Asha live

While the user talks with Asha hands-free, the phone opens a second WebSocket, `wss://api.circaso.com/v1/assistant/voice/live`, only for that conversation. Like the main socket, the first frame authenticates, with the user token only: `{"type":"auth","accessToken":"…","agentToken":"…","language":"en","chatReading":true}`. The server answers `{"type":"ready","voiceSecondsLeft":…,"audio":{"encoding":"mulaw","sampleRate":8000}}`. Access tokens last 15 minutes: send the same `auth` frame again with fresh tokens before they expire.

Binary frames carry audio both ways: the phone streams Opus (16 kHz mono), and Asha's voice comes back as mulaw at 8 kHz (as the `ready` frame says). No frame is larger than 64 KB, either way. Text frames are JSON with a `type`:

| `type` | From | Meaning |
| --- | --- | --- |
| `transcript` `{text, final}` | server | The words heard so far. |
| `turn` `{text}` | server | The user finished; Asha answers it exactly like a typed message (same tools, allowance and history). |
| `reply` `{text}` | server | One sentence of the answer, sent about when the audio reaches it (a caption, not an exact cue). Each sentence is spoken as soon as the model has written it. |
| `reply_done` `{message, spoken}` | server | The answer as saved in the Asha chat, once its audio has played (`spoken: false` if talked over). |
| `tool_request` `{turnId, callId, name, arguments}` | server | As on the chat route (`arguments` is a JSON string); answer within 30 seconds with a `tool_results` frame `{turnId, callId, snippets}`. |
| `interrupted` | server | The user talked over Asha: stop playing. Their new words become the next turn. |
| `error` `{code, message}` | server | One answer failed; keep talking. |
| `timing` `{turn, modelFirstTextMs, firstSentenceMs, firstAudioMs}` | server | Once per turn: milliseconds from the end of the user's speech to the model's first words, the first sentence sent to the voice, and the first audio sent; `null` for a step the turn never reached. For latency logs; safe to ignore. |
| `closing` `{reason, message?, retryAfterSeconds?}` | server | The server is about to close; `reason` is also the close reason. |
| `auth` `{accessToken, agentToken}` | phone | Fresh tokens, mid-conversation. |
| `end` | phone | Hang up. |

A frame with a `type` the phone doesn't know is ignored: new types are added without a new version.

The server closes after 30 seconds of silence (`silence`), when the day's voice allowance runs out (`assistant.quota_exceeded`), or when a speech provider fails (`assistant.model_unavailable`), each after a `closing` frame. It closes with 1008 `unauthorized` and no frame when the session is signed out or its token expires (checked at each turn and every minute), and with 1009 `Frame is too big: N` (Ktor's reason, N the frame's size) and no frame after a frame over 64 KB. Voice seconds count only speech: the user's, timed by the transcriber from the first word to the last, and Asha's audio as it plays (what a barge-in cuts off is free); an open mic is free. The agent token is checked at every turn and every `auth` frame: another user's closes the socket with 1008 `unauthorized`. A language without a live voice, such as Swahili, is refused at once with `assistant.language_not_supported`: record a clip for *Transcribe a voice clip* and read the reply aloud with the phone's own voice. Audio is never stored.

## Realtime

While the app is open it keeps one WebSocket to `wss://api.circaso.com/v1/ws`. The first frame authenticates: `{"type":"auth","accessToken":"…","last":[{"module":"users","seq":1042}]}`, where `last` lists the last `seq` the app saw, one entry per module (leave it out, or send `[]`, on the first connect). The server then sends what was missed, and after that every frame has the same shape:

```json
{ "module": "users", "type": "contact.joined", "id": "0193…", "seq": 1043, "ts": "2026-10-21T18:07:12Z", "data": { "userId": "…", "displayName": "Amina" } }
```

`seq` rises per user per module, so a gap means a missed frame. A `resync` frame means the gap is older than 7 days: reload that module's data over HTTP. Frames that are never replayed (`messaging` / `message.new` and `typing`) carry `seq` 0: don't record it as the last you saw; a missed message is in the inbox (`GET /v1/inbox`). When the app is closed the same news arrives as a push notification, and the socket sends a ping every 25 seconds. Chat pushes are data-only: they carry `chatId` and `messageId`, the app fetches the inbox, decrypts and shows the notification itself (on iOS in a Notification Service Extension, which receives a placeholder "New message" alert to replace).

The wallet's frames (module `wallet`, replayed like `users`): `transfer.updated` `{transferId, state, blockchain, amount, txHash?, failureReason?}` to the sender each time a payment moves (pushed on `CONFIRMED` and `FAILED`: "Payment sent" / "Payment failed"); `payment.received` `{transactionId, blockchain, amount, fromUserId?, fromName?}` once money reaches you on chain (pushed: "Amina sent you money"); `wallet.ready` `{blockchain}` when your wallet turns `ACTIVE` (no push); `setup.requested` `{fromUserId, fromName}` when a contact wants to pay you (pushed). Amounts are `{asset, amountMinor, amount}` strings; a push never shows an amount.

After the auth frame the app may send small frames of its own, `{"module":"messaging","type":"ack","data":{…}}`: only acknowledgements, read receipts, typing and presence travel this way, and everything else is an HTTP call. The server passes each to the module it names and does not reply; a frame for a module that takes none is dropped. Messaging takes `ack` `{messageIds}` (delivered: the messages leave the inbox and their senders get a receipt), `read` `{chatId, upToMessageId}` (read up to there; the sender gets a receipt unless you turned read receipts off) and `typing` `{chatId, on}` (relayed to the chat's other members in chats of 50 people or fewer, unless you turned typing off).

A socket may send at most **100 frames per second**. Frames over the limit are dropped without a reply and the socket stays open, so slow down rather than reconnect. A single frame may be at most **64 KB** (65,536 bytes); a bigger one closes the socket.

When the server closes the socket it says why:

| Code | Reason | Meaning |
| --- | --- | --- |
| 1008 | `auth required` | The first frame wasn't an auth frame, or didn't arrive within 10 seconds |
| 1008 | `invalid last` | The auth frame's `last` isn't a list of `{"module","seq"}` with whole numbers of 0 or more, such as `[{"module":"users","seq":1042}]`, or names a module twice |
| 1008 | `unauthorized` | The access token is expired, revoked or not an app token: refresh it, then reconnect |
| 1008 | `session revoked` | Sent after a `system` / `session.revoked` frame: this session ended (signed out, a new phone, or the account closed) |
| 1009 | `Frame is too big: …` | A frame over 64 KB |

Signing out (`POST /v1/auth/signout`) closes that session's socket the same way, after the `session.revoked` frame.

## Health and webhooks

`GET /health/live` and `GET /health/ready` need no token and are for load balancers and monitoring. Vendors (SMS delivery, email, payments) call `POST /v1/hooks/{vendor}`; the gateway checks their signature, rejects old or repeated deliveries and answers `200` at once. Circle (`/v1/hooks/circle`, wallet transactions) is the exception: the gateway waits until the update is applied and answers an error when it can't be, so Circle delivers it again. `HEAD /v1/hooks/{vendor}` answers `200` for a registered vendor (and `404` otherwise), for vendors that check the address first. These are in the **Infrastructure** section.
