# Identity — sign-in flow

Every screen and state in Circaso sign-up, sign-in, recovery and account security, so each one can be designed. Sign-in is Google or Apple only; a verified phone number and a registered device complete the account; there is no self-service recovery. Screen ids (ID-xx) are used throughout; each screen's backend call is in Backend touchpoints at the end, numbered as on the Identity tab.

## Overview

**How a user proves who they are**

| Factor | Proves | Used when |
| --- | --- | --- |
| Google / Apple account | "I control this sign-in account" | Every sign-in |
| Phone number | "I control this SIM" | Sign-up, and every sign-in on a new phone |
| Device key | "This request comes from my registered phone" | Every session refresh, and signing back in on the same phone |
| Identity verification (KYC) | "I am this person" | Only when support restores a lost account |

**Rules the screens must respect**

- One phone number belongs to exactly one account. A number already in use is blocked, never merged.
- One active phone per account. Signing in on a new phone signs the old one out.
- An account is usable only once its phone is verified and this phone's device key is registered (ID-11). Before that it is incomplete: no wallet, not visible to contacts.
- Same phone signing back in goes straight in. A new phone always re-verifies the number.
- **No self-service recovery.** The Google or Apple sign-in is the account. If it is lost, only Circaso support can restore access, and only for an account that completed identity verification (KYC). An unverified account that loses its sign-in is gone, and so is access to its wallets, because Circle wallets can only be used through Circaso.
- Support restores an account only after a 48-hour notice to the old phone and email, which can cancel it.

**Design principles**: no passwords anywhere; one decision per screen; Android should need almost no typing (carrier verification, number hint, SMS auto-read); every security moment explains itself in one plain sentence.

## Account states

The app decides which screen to show from the account's state plus whether this phone is the registered device. The backend status is in brackets.

| State | How it gets there | User can | User sees on sign-in |
| --- | --- | --- | --- |
| Pending phone (`PENDING_PHONE`) | Google/Apple sign-in done; phone not verified yet, or verified but the device not registered yet | Nothing except finish sign-up | Phone number step, or biometrics if the number is already verified |
| Active (`ACTIVE`) | Phone verified and device registered (ID-11) | Everything | Home (same phone) or phone re-verify (new phone) |
| Recovery pending (`RECOVERY_PENDING`) | Support accepted a recovery request; the 48-hour notice is running | Nothing; the old phone can cancel | "Your account is being recovered" with the time left |
| Restored, on hold | Support relinked the account in the last 48 h | Chat, receive money; outgoing transfers held | Home with a hold banner and countdown |
| Suspended (`SUSPENDED`) | Ops or the auditor flags the account | Read-only, contact support | Suspended screen with support link |
| Blocked (`BLOCKED`) | Terms violation confirmed | Nothing | Blocked screen, no way back in-app |
| Deleted (`DELETED`) | The user deleted the account; still in its grace period | Nothing | "This account was deleted" |

Incomplete (pending phone) accounts are deleted automatically after 30 days. Only the move to Active announces the account to the rest of Circaso (`AccountActivated`), so a half-registered user never gets a wallet or shows up in contacts.

## Flow map

> **Diagram:** identity flow map · 4 entry paths into Home (see the diagram in the Claude Docs version of this document)

The account's status after Google/Apple sign-in picks the lane; every lane except suspended and blocked ends in Home.

## Flow A: first-time sign-up

A new user goes Welcome → Google/Apple → phone → biometrics → profile, about five screens, most of them one tap on Android. The account goes Active at the biometrics step (ID-11), when the phone registers its device key. Identity verification (KYC) comes later, from Home, not during sign-up.

### ID-01 Launch

- No visible design beyond the splash/logo. The app checks for a stored session and the device key, then routes: nothing stored → ID-02; session valid → Home; session expired but device key present → silent refresh → Home; refresh rejected → ID-02 with a short "You were signed out" note.
- States: checking (logo only, under 1 s), offline (logo + "No connection", retry), app too old → ID-30 Force update.

### ID-02 Welcome

- Logo, one-line promise, two buttons: **Continue with Google**, **Continue with Apple**. On iOS, Apple sits first; on Android, Google first.
- Small print: Terms and Privacy links, and "By continuing you agree…". A **Lost access to your account?** link (→ ID-16).
- No email, no password, no "create account" vs "sign in" split: the same buttons do both.
- States: default, loading after tap (button spinner, other button disabled), cancelled (back to default silently), error (inline: "Couldn't reach Google. Try again.").

### ID-03 Google / Apple sheet (system)

- Drawn by the OS, not designed. Design only the return: a full-screen "Signing you in…" while the backend checks the token and decides where to go next.
- Backend decides (Identity → sign-in decision table): brand-new → ID-04; active on this phone → Home; active on another phone → ID-13; pending phone → ID-04 with "Finish setting up" copy; number verified but no device yet → ID-11; recovery pending, suspended, blocked or deleted → their screens.

### ID-04 Your phone number

- Title: "Add your number". Reason line: "Friends find and pay you by your number. We'll verify it once."
- Field with country picker, default Kenya +254.
- **Android, carrier verification supported** → a single **Verify with one tap** button that opens the system consent (ID-05). No typing.
- **Android, not supported** → tapping the field opens Google's number picker showing the SIM numbers; picking one fills the field. User taps **Send code** → ID-06.
- **iOS** → user types the number, taps **Send code** → ID-06.
- States: empty, invalid format (inline), checking, number already registered → ID-07, too many attempts ("Try again in 10 minutes").

### ID-05 Carrier consent (system, Android)

- System dialog asking to share the SIM's number. On approval the number is verified with no SMS → straight to ID-11. On decline or failure → fall back to the SMS path on ID-04 with the number prefilled if known.

### ID-06 Enter the code

- "Enter the 6-digit code sent to +254 7•• ••• 678" with **Edit number**.
- Android reads the SMS automatically and fills the boxes; iOS offers the code above the keyboard. Auto-submits on the 6th digit.
- Resend link with a countdown (30 s, then longer each time).
- States: waiting, auto-filled and verifying, wrong code (shake + attempts left), expired, locked after too many tries, number already registered → ID-07.

### ID-07 Number already registered

- "This number already has a Circaso account." Explain: sign in with the Google or Apple account used before.
- Actions: **Sign in with a different account** (back to ID-02), **I can't access that account** (→ Flow C, support), **Use a different number** (back to ID-04).
- Never reveal which Google/Apple account the number belongs to.

### ID-08 Verify your identity (KYC prompt)

- Not part of sign-up. Shown as a card on Home, and required before the wallet balance passes a set limit (amount to decide).
- Copy: "Verify your identity so we can restore your account if you ever lose your Google or Apple login. Without it, a lost login means lost access to your money."
- The steps themselves (ID document, live selfie) belong to the KYC flow, designed with the wallet screens.
- Actions: **Verify now**, **Later**. Later keeps the card on Home.
- ID-09, ID-10, ID-19 and ID-20 (the old recovery-key screens) are retired.

### ID-11 Protect your money

- "Use Face ID / fingerprint to confirm payments." One system prompt to enrol. If the phone has no biometrics, the device PIN is used.
- The device key is created silently here and registered with the account; on sign-up this is the moment the account goes Active.
- Action: **Turn on** (no skip: every payment needs it).

### ID-12 Hand-off to profile setup

- Identity is done and the account is Active. Next screens belong to the users module: name and photo, @handle, contacts and notification permissions (see the Users tab).

## Flow B: returning user

A returning user on the same phone signs in with one tap and lands in Home; on a new phone they also re-verify their number, and the old phone is signed out.

### B1 Same phone, after signing out

- ID-02 Welcome → Google/Apple.
- The phone still holds its device key (signing out ends sessions but keeps the phone registered), so the backend recognises it from the signed device proof.
- Straight to Home. No phone step.

Design note: "Sign out" and "Remove this phone" are different actions in Settings. Removing the phone deletes the key, so the next sign-in counts as a new phone.

### B2 New phone

- ID-02 Welcome → Google/Apple.
- **ID-13 New phone** — "Verify your number to use Circaso on this phone. Circaso will sign out on your other phone." Shows the registered number masked (+254 7•• ••• 678).
- ID-04 / ID-05 / ID-06 — same verification as sign-up. The number must match the one on the account.
- ID-11 Protect your money — biometrics and a new device key for this phone.
- **ID-14 Restoring your chats** — short progress screen while conversations and balances load from the server, then Home.

On the old phone: **ID-15 Signed out on this phone** — "Circaso is now active on another phone (Samsung A54, today 14:02)." Actions: **OK**, **This wasn't me** (→ contact support, who can freeze the account).

### B3 Incomplete account

- ID-02 → Google/Apple.
- Account is still pending phone → ID-04 with the title "Finish setting up" and the rest of Flow A from there.

### B4 Wrong number on a new phone

- On ID-04/ID-06, the verified number differs from the account's number → inline error: "This isn't the number on your account (+254 7•• ••• 678)."
- Actions: **Try again**, **I changed my number** (→ Flow C: support verifies the person and moves the account to the new number).

### B5 Session refresh (no screen)

- Access tokens expire every 15 minutes and refresh silently with a device signature. Only a rejected refresh (revoked, suspended, replaced by another phone) shows a screen: ID-15 for replaced, ID-21 for suspended, otherwise ID-02 with "You were signed out".

## Flow C: support-led recovery

There is no in-app recovery. If a user loses their Google/Apple sign-in or the number on the account, only support can restore access, and only for an account that completed KYC. The user id doesn't change, so the Circle user and its wallets carry over intact.

**Entry points: Lost access to your account?** on ID-02; **I can't access that account** on ID-07; **I changed my number** on B4; **This wasn't me** on ID-15 and ID-25.

### ID-16 Lost access to your account?

- Two lines on the rule: verified accounts can be restored by support; unverified accounts can't.
- Fields: the phone number on the account and a contact email. Action: **Contact support**, which opens a support request.
- Account not verified → "This account wasn't verified, so we can't restore it." Options: start a new account, or release the number (below).

### ID-17 Request submitted

- What happens next and how long it takes: identity check against the KYC record (ID document and a live selfie), then a 48-hour notice during which the old phone and email are told and can cancel.
- On reopening, the request shows its status: received, checking, notice sent (with countdown), restored, declined or cancelled.

### ID-18 Account restored

- New Google/Apple sign-in and/or new number linked; all other phones signed out.
- Continues to ID-04 to ID-06 if the number changed, then ID-11 biometrics, then Home with the hold banner.

### ID-23 Hold banner (on Home)

- Persistent banner: "Payments resume in 31 h" with a countdown, tap for details. A send attempt during the hold shows the same message instead of the payment sheet.

**Releasing a number from a lost, unverified account:** the user proves the SIM with an SMS code and confirms the old account is abandoned. After a 7-day notice that the old phone can cancel, the old account is closed (its wallets stay inaccessible) and the number becomes free for a new sign-up.

**Notifications:** push to the old phone and an email to the old sign-in address when a request is opened and again when it completes: "Someone asked to restore your Circaso account. Wasn't you? Tap here."

## Settings screens

All identity controls live under one **Account & security** screen; every change there that could lock someone out or move access asks for biometrics first.

### ID-24 Account & security

- **Phone number** — shown masked, with a "verified" mark. Changing it goes through support (Flow C) for now.
- **Sign-in accounts** — Google and Apple rows, each Linked / Link. Unlink is allowed only if the other provider stays linked. Linking both is the best protection against losing access.
- **Identity verification** — Verified / Not verified, with **Verify now** (ID-08) and one line on why: it is what makes recovery possible.
- **This phone** — model and "active since" date.
- **Security activity** → ID-25.
- **Sign out** → ID-26. **Remove this phone** → ID-27. **Delete account** → ID-28.

### ID-25 Security activity

- Newest-first list: sign-ins, new phones, number verifications, recovery, sign-in accounts linked or unlinked. Each row: what, phone model, date and time.
- A row the user doesn't recognise has **This wasn't me** → support and Flow C.

### ID-26 Sign out

- Confirm sheet: "You can sign back in with Google or Apple. Your chats and money stay safe." Signing out keeps this phone registered, so sign-in later is one tap (B1).

### ID-27 Remove this phone

- For selling or giving away the phone. Deletes the device key and local data. Next sign-in on any phone re-verifies the number.

### ID-28 Delete account

- Required by Apple and Google store rules. Explain consequences: chats gone, number and @handle released after a waiting period.
- If the wallet holds money, deletion is blocked until it is sent out, with a clear message and a shortcut to send.
- Biometrics, then a final typed confirmation ("DELETE").

### ID-21 Suspended / ID-29 Blocked / ID-30 Force update

- Suspended: "Your account is paused while we review activity." Read-only access, support link, no reason detail that helps evade review.
- Blocked: "This account can no longer use Circaso." Support link only.
- Force update: the app version is below the minimum; one button to the store.

## Errors and edge cases

Each case below needs a designed state, not a generic error toast.

| Situation | Where | What the user sees |
| --- | --- | --- |
| No connection | Any step | Inline "No connection" with Retry; entered data kept |
| App closed mid sign-up | ID-04 to ID-11 | Next launch resumes at the unfinished step ("Finish setting up") |
| Google/Apple sheet cancelled | ID-03 | Back to ID-02, no error |
| Apple hides the email | ID-03 | Nothing; email is optional, the account keys on the Apple id |
| Dual-SIM phone | ID-04, ID-05 | Number picker lists both SIMs; carrier check runs on the chosen one |
| SMS doesn't arrive | ID-06 | Resend after countdown, Edit number; after 3 resends, "Try again later" |
| Too many code requests or wrong codes | ID-04, ID-06 | Lock message with the wait time |
| Number already on another account | ID-04, ID-06 | ID-07 |
| New phone, different number | B2 | B4 inline error |
| Recovery asked for an unverified account | ID-16 | "This account wasn't verified, so we can't restore it", with the number-release option |
| Recovery request declined | ID-17 | Declined status with the reason category and a support link |
| Account being recovered | ID-03 | "Your account is being recovered" with the time left |
| No biometrics on the phone | ID-11 | Device PIN used instead; copy adjusts |
| Biometrics enrolment declined | ID-11 | Cannot continue; explain payments need it, offer retry |
| Device check fails (rooted phone, sideloaded app) | ID-11 | "Circaso can't run on this phone"; release builds require Play Integrity or App Attest |
| Sign-in from a suspended or blocked account | ID-03 | ID-21 or ID-29 |
| Old phone after a new phone signs in | Old phone | ID-15 on next open or via push |
| App version too old | ID-01 | ID-30 Force update |
| Backend down | Any step | "Circaso is having trouble. Try again in a few minutes." |

**Rooted or jailbroken phones** are settled by device attestation (Identity → I6): release builds from the stores must pass Play Integrity or App Attest to register a device, so they can't be used on a rooted or tampered phone. Debug builds, which only reach staging, are not checked.

## Screen inventory

25 screens and states to design; ID-03 and ID-05 are system sheets and only need their before and after states. Tick each one off as it lands in the design file.

**Sign-up (Flow A)**

- [ ] ID-01 Launch, plus offline state
- [ ] ID-02 Welcome, with "Lost access?" link
- [ ] ID-03 "Signing you in…" return state (system sheet)
- [ ] ID-04 Your phone number: carrier one-tap, number-hint, iOS typed variants
- [ ] ID-05 Carrier consent fallback state (system dialog)
- [ ] ID-06 Enter the code
- [ ] ID-07 Number already registered
- [ ] ID-08 Verify your identity (KYC prompt card on Home)
- [ ] ID-11 Protect your money (biometrics)
- [ ] ID-12 Hand-off to profile setup

**Returning user (Flow B)**

- [ ] ID-13 New phone
- [ ] ID-14 Restoring your chats
- [ ] ID-15 Signed out on this phone (old phone)

**Recovery (Flow C)**

- [ ] ID-16 Lost access to your account?
- [ ] ID-17 Request submitted, with its status states
- [ ] ID-18 Account restored
- [ ] ID-23 Hold banner on Home

**Settings and account status**

- [ ] ID-24 Account & security
- [ ] ID-25 Security activity
- [ ] ID-26 Sign out sheet
- [ ] ID-27 Remove this phone
- [ ] ID-28 Delete account
- [ ] ID-21 Suspended
- [ ] ID-29 Blocked
- [ ] ID-30 Force update

## Backend touchpoints

Each screen maps to one identity call (numbers as on the Identity tab, paths under `/v1`). Until the phone is verified and the device registered, the app holds an `onboarding` session that can reach only the sign-up and recovery calls.

| Screen | Call | Returns or does |
| --- | --- | --- |
| ID-01 | I7 `POST /auth/refresh` (device-signed) | New token pair, or a reason: replaced, suspended, revoked |
| ID-03 | I1 `POST /auth/nonce`, then I2 `POST /auth/social` (provider, ID token, device, device proof if this phone has a key) | `next`: `VERIFY_PHONE`, `REVERIFY_PHONE`, `REGISTER_DEVICE` or `DONE`, with an onboarding or app session; or a status error |
| ID-04 | I3 `POST /auth/phone/start` | Sends the SMS code; rejects a number already on another account |
| ID-05 | I5 `POST /auth/phone/carrier` (carrier token) | Number verified with no SMS |
| ID-06 | I4 `POST /auth/phone/verify` (code) | Number verified and claimed; the account stays pending until ID-11 |
| ID-11 | I6 `POST /devices` (public key, attestation, push token) | Device registered, account Active on sign-up, previous phone replaced, app session issued |
| ID-15 | WebSocket `system.session.revoked`, or push | Old phone shows the signed-out screen |
| ID-16 | I15 `POST /recovery/requests` (phone, contact email) | Opens a support case; refused for unverified accounts |
| ID-16 (release) | I16 `POST /auth/phone/release` | Starts the 7-day number release |
| ID-17 | I17 `GET /recovery/requests/{requestId}` | Request status |
| Support tool | `POST /ops/recoveries/{requestId}/approve` (`ops` scope) | After the KYC match and the 48-hour notice: relinks the sign-in or number, revokes other phones, sends notifications |
| ID-24 | I11 `GET /account/security`, I12 `POST` / `DELETE /account/providers/{provider}` | Security overview; link or unlink Google/Apple |
| ID-25 | I13 `GET /account/security-log` | Security activity, newest first |
| ID-26 | I8 `POST /auth/signout` | Sessions ended, phone stays registered |
| ID-27 | I10 `DELETE /devices/current` | Device key and sessions removed |
| ID-28 | I14 `DELETE /account` | Deletion scheduled once every `DeletionGuard` agrees; refused while the wallet holds money |

The users module takes over at ID-12: it hears `AccountActivated` and creates the profile the next screens fill in.
