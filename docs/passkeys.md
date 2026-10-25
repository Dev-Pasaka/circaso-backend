# Passkeys on circaso.com

The chat history-key backup is sealed under a passkey (messaging, M21; decision 2026-10-25). A passkey belongs to a website domain, its **relying party**, and here that domain is `circaso.com`. Android and iOS create a passkey for an app only when that domain says the app may use it, which it does through two files at fixed addresses. The passkey isn't used to sign in, so the backend has nothing to verify; this is website and app setup only.

Both files must be served:

- at exactly these paths, over HTTPS, with **no redirects** (not to `www.`, not to a trailing slash);
- as `Content-Type: application/json`;
- reachable by Google's and Apple's crawlers: no Cloudflare Access, bot challenge or login on `/.well-known/`.

| File | Path |
| --- | --- |
| Android | `https://circaso.com/.well-known/assetlinks.json` |
| iOS | `https://circaso.com/.well-known/apple-app-site-association` (no extension) |

## What you need first

| Value | Where to find it |
| --- | --- |
| Android package name | `applicationId` in the app's Android Gradle file (for example `com.circaso.app`, and `com.circaso.app.debug` if debug builds use a suffix) |
| Android SHA-256 fingerprints | One per signing key. **Play App Signing key**: Play Console → the app → Test and release → App integrity → App signing → "App signing key certificate" → SHA-256. **Upload key** (same page). **Debug key**: `./gradlew signingReport`, or `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android` |
| Apple Team ID | developer.apple.com → Account → Membership details (10 characters, also top right of the developer account) |
| iOS bundle id | `com.circaso.app` (already configured for Sign in with Apple) |

Google sign-in uses the **SHA-1** of the same keys; passkeys need the **SHA-256**. The Play App Signing key is the one that matters for installs from Google Play, because Google re-signs the app with it.

## Android: `assetlinks.json`

```json
[
  {
    "relation": [
      "delegate_permission/common.handle_all_urls",
      "delegate_permission/common.get_login_creds"
    ],
    "target": {
      "namespace": "android_app",
      "package_name": "com.circaso.app",
      "sha256_cert_fingerprints": [
        "AB:CD:…:EF",
        "12:34:…:56"
      ]
    }
  }
]
```

- `get_login_creds` is the one passkeys need; `handle_all_urls` also lets `https://circaso.com/i/…` invite links open the app, which users' invites already use.
- List every fingerprint that signs a build people install: Play App Signing, upload key, and debug if debug builds should make passkeys. A debug build with its own package name needs a second entry with that `package_name`.
- Check it: `https://digitalassetlinks.googleapis.com/v1/statements:list?source.web.site=https://circaso.com&relation=delegate_permission/common.get_login_creds` should list the app.

**In the app:** Credential Manager (`androidx.credentials`), relying party id `circaso.com`, with the PRF extension in the request (`"extensions": { "prf": { "eval": { "first": "<salt>" } } }`). Not every phone and password manager returns a PRF result, so the app checks for it and offers the recovery key when it's missing.

## iOS: `apple-app-site-association`

```json
{
  "webcredentials": {
    "apps": ["ABCDE12345.com.circaso.app"]
  },
  "applinks": {
    "details": [
      { "appIDs": ["ABCDE12345.com.circaso.app"], "components": [ { "/": "/i/*" } ] }
    ]
  }
}
```

- `ABCDE12345` is the Team ID. `webcredentials` is the part passkeys need; `applinks` opens invite links in the app.
- Apple doesn't fetch the file from the site directly: its CDN does, and caches it for up to a day. Check what Apple holds: `https://app-site-association.cdn-apple.com/a/v1/circaso.com`.
- While developing, add `?mode=developer` to the entitlement (below) and turn on Settings → Developer → Associated Domains Development on the test iPhone, so iOS fetches the file straight from the site.

**In the app:** Xcode → the iOS target → Signing & Capabilities → **+ Capability → Associated Domains**, add `webcredentials:circaso.com` and `applinks:circaso.com`. In the Kotlin Multiplatform project this goes in `iosApp`'s `.entitlements` file. Passkeys with PRF need iOS 18 or later (`ASAuthorizationPlatformPublicKeyCredentialProvider` with the `prf` input); older iPhones get the recovery key.

## Serving the files

If circaso.com is on **Cloudflare Pages** (or Workers static assets): put both files in the site's `public/.well-known/` folder, and add to `public/_headers`:

```
/.well-known/apple-app-site-association
  Content-Type: application/json
/.well-known/assetlinks.json
  Content-Type: application/json
```

`assetlinks.json` gets the right type from its extension; the Apple file has none, so it needs the header. Then check, from outside:

```bash
curl -sI https://circaso.com/.well-known/apple-app-site-association   # 200, content-type: application/json, no Location
curl -s  https://circaso.com/.well-known/assetlinks.json | jq .
```

On another host the rule is the same: two static files, a 200 with `application/json`, and no redirect. In Cloudflare, also make sure Bot Fight Mode or a WAF rule doesn't challenge `/.well-known/*`; a challenge page looks like a missing file to Google and Apple.

## Not done yet

- The values: package names, the Play App Signing SHA-256 (it exists once the app is first uploaded to Play Console) and the Team ID. Until the app is on Play, the upload key and debug fingerprints are enough for testing.
- Who owns the circaso.com site, so the two files are part of its deploy.
