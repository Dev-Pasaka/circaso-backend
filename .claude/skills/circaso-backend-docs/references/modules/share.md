# share

Built 2026-11-06. **Share links**: every share in the app (a payment receipt, a group statement, an invite, a "pay me" card, a wallet address) sends a short link, `<SHARE_BASE_URL>/s/{code}`, to a public, server-rendered page instead of plain text (decision 2026-11-06). Schema `share`, Redis prefix `shr:`, no stream of its own (it publishes no events; reads `events:identity`), no realtime frames. The contract the apps build against is `docs/mobile-integration.md` → Share links.

## Overview

### Owns
- Share links: a 12-character base62 code (about 71 bits, `SecureRandom`), its owner, kind, the **snapshot** the page shows (typed JSON), when it ends (optional) and when it was turned off.
- Pay links' page (`GET /pay/{address}?chain=…&asset=USDC[&amount=…]`, decision 2026-11-24): stateless, nothing stored.
- The public page (`GET /s/{code}`) and the app association files (`/.well-known/assetlinks.json`, `/.well-known/apple-app-site-association`), mounted at the root through `PageContributor`, outside `/v1`.

### Contract (`share:api`)
- `ShareCode` only. Nothing asks share anything.

### Integrations used
- `ExchangeRates` (integrations:api) for the fiat value on a receipt or statement, when the sharer's app passes a currency.

### Depends on
- `wallet:api` `Wallets.transfer` (receipts: parties, state, chain, hash, times; added `blockchain`, `txHash`, `createdAt`, `updatedAt` to `TransferSummary`) and `Wallets.depositAddress` (new: the person's own ACTIVE address on a chain).
- `chama:api` `Chamas.statement` (new: the member's view of the target and the latest 50 ledger lines with names; null for a non-member or a group without a target).
- `users:api` `Profiles` (names, handle) and `Invites` (new: a fresh U12 invite for the sharer).
- `messaging:api` `ChatDirectory.isMember` (SH4: may this viewer open the group).
- `identity:api` events: `AccountStatusChanged` to `DELETED` and `AccountDeleted` delete every link the person made.

## Endpoints

| ID | Route | Scope | Use case | Notes |
| --- | --- | --- | --- | --- |
| SH1 | `POST /share/links` `{kind, transferId?, chatId?, blockchain?, showNames, currency?, expiresInDays?}` | app | `CreateShareLink` | the snapshot is built from the owning modules (`Snapshots`), never from the request; whatever isn't the caller's answers `share.source_not_found`; an invite's link ends with its invite (7 days); gateway limit `share` 30/hour/user |
| SH2 | `GET /share/links?cursor=&limit=` | app | `ListShareLinks` | live links only, newest first; keyset cursor (createdAt, code) |
| SH3 | `DELETE /share/links/{code}` | app | `RevokeShareLink` | owner only; clears the snapshot, stamps `revoked_at`, keeps the row 30 days |
| SH4 | `GET /share/links/{code}` | app | `OpenShareLink` | what the page shows plus `open`, decided for this viewer (a party to the payment, a member of the group, someone else's invite or pay me) |
| — | `GET /s/{code}` | public | `ViewSharePage` | HTML; `shr:views:{hash(ip)}` 60/minute before the code is read; 200 live, 410 off or ended, 404 unknown, 429 throttled; `no-store`, `X-Robots-Tag: noindex`, strict CSP (inline style and `img-src https://assets.circaso.com` only, no script) |
| — | `GET /pay/{address}?chain=&asset=&amount=` | public | `ReadPayLink` | HTML; a pay link (decision 2026-11-24): the address grouped in fours with Copy, the network and coin large, the warning, "Open in Circaso" (`circaso://pay/…`). Strict: EIP-55 when mixed case, a 32-byte base58 key on SOL chains, `chain` one of BASE, BASE-SEPOLIA, MATIC, MATIC-AMOY, ETH, ETH-SEPOLIA, SOL, SOL-DEVNET, `asset` USDC (or absent), `amount` a decimal with at most 6 places (else not shown, and said). 200 payable, 404 bad address, 400 unknown chain or coin; no address is ever shown on those. `no-store`, `noindex`, the share CSP plus `script-src 'sha256-…'` for the one Copy script. No throttle: nothing is looked up |
| — | `GET /.well-known/assetlinks.json` | public | — | `SHARE_ANDROID_PACKAGE` + `SHARE_ANDROID_CERT_SHA256`; 404 while no fingerprint is set |
| — | `GET /.well-known/apple-app-site-association` | public | — | `SHARE_IOS_APP_IDS`, components `/s/*` and `/pay/*` |

## Data

`share.links`: `code` (PK), `owner_id`, `kind`, `snapshot` (typed JSON, NULL once revoked), `created_at`, `ends_at`, `revoked_at`, `expires_at` (row expiry: 30 days after it ends or is revoked; NULL while it may still open). Indexes `(owner_id, created_at, code)` for SH2 and account deletion, `(expires_at)` for `share.purge-expired` (every 30 minutes). A receipt still on its way is brought up to date (status, hash) when opened.

## Error codes

`share.request_invalid` (400), `share.source_not_found` (404), `share.not_shareable` (400: an unsigned payment, a group without a target, pay me without a @handle), `share.link_not_found` (404), `share.unavailable` (503: users couldn't make the invite).

## Configuration

`SHARE_BASE_URL` (no trailing slash; dev `https://sandbox.circaso.com`, production the short domain), `SHARE_ANDROID_PACKAGE`, `SHARE_ANDROID_CERT_SHA256` (comma separated), `SHARE_IOS_APP_IDS`, `SHARE_ANDROID_STORE_URL`, `SHARE_IOS_STORE_URL`, `SHARE_WEBSITE_URL`, `SHARE_PREVIEW_IMAGE_URL`, `SHARE_VIEWS_PER_MINUTE`.

## Group join links (2026-11-14)

Share also draws the public page of a group's join link, which is messaging's (M28 to M32): `GET /s/{token}` where the token has 22 characters (a share code has 12) shows the name, description, photo and member count the group's admin published, with "Join group" (`circaso://s/{token}`) and the same guards as every page (`noindex`, `no-store`, no script; the CSP also allows images from the page's own host). `GET /s/{token}/photo` serves the published photo (5-minute cache, `?v=` changes with the photo). Open Graph tags carry the group's name, description and photo. A reset, turned-off or unknown token answers 410 "This link no longer works". Views and photo fetches count against the per-address limit. SH4 answers a join token from an older app with `kind: "group"`, a title and "Update Circaso to join this group."; nothing is stored in `share.links` for it.

