package com.circaso.app

import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.hide
import io.ktor.server.routing.route

/**
 * Local runs only: `/dev/social`, a page that walks a real sign-up from a browser with a real Google account: nonce, the
 * Google button, `POST /auth/social`, a phone number and the code (printed in the server console), then a device key made in
 * the browser, which turns the account active. It exists because the phone app does not yet. Not in the API docs, and not
 * registered outside `local`. The Google client id is not a secret; it is the only thing put into the page.
 * The Web client in Google Cloud needs `http://localhost` and `http://localhost:<port>` as authorized JavaScript origins.
 */
fun Route.devSocialRoutes(googleClientId: String) {
    route("/dev") {
        get("social") {
            call.respondText(DEV_SOCIAL_PAGE.replace("__CLIENT_ID__", googleClientId), ContentType.Text.Html)
        }.hide()
    }
}

private val DEV_SOCIAL_PAGE = """
<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Circaso sign-up test</title>
<script src="https://accounts.google.com/gsi/client" async defer></script>
<style>
  :root{color-scheme:light dark;font:16px/1.5 -apple-system,BlinkMacSystemFont,"SF Pro Text",system-ui,sans-serif}
  body{max-width:640px;margin:32px auto;padding:0 16px}
  h1{font-size:28px;letter-spacing:-.03em;margin:0 0 4px} .muted{opacity:.65;font-size:14px}
  section{border:1px solid #8884;border-radius:16px;padding:16px;margin:16px 0} section.off{opacity:.4;pointer-events:none}
  h2{font-size:15px;margin:0 0 10px} input,button{font:inherit;padding:9px 12px;border-radius:10px;border:1px solid #8886}
  button{background:#0b0b0c;color:#fff;border:0;cursor:pointer} input{width:100%;box-sizing:border-box;margin-bottom:8px}
  pre{background:#8882;border-radius:10px;padding:10px;overflow:auto;font-size:12.5px;margin:10px 0 0}
</style></head><body>
<h1>Sign-up test</h1><p class="muted">Local only. Talks to this server with a real Google account; nothing here is stored except by the app.</p>

<section id="s1"><h2>1. Google</h2><div id="gbtn"></div><pre id="o1" hidden></pre></section>
<section id="s2" class="off"><h2>2. Phone number</h2>
  <input id="phone" placeholder="+254712345678" autocomplete="off"><button id="send">Send code</button>
  <p class="muted">The code is printed in the server's console (the SMS provider is <code>log</code>).</p>
  <input id="code" placeholder="6-digit code" autocomplete="off" hidden><button id="verify" hidden>Verify</button><pre id="o2" hidden></pre></section>
<section id="s3" class="off"><h2>3. This browser as the phone</h2>
  <p class="muted">Makes a P-256 key in the browser (as a phone would in its secure hardware) and registers it. The account turns active.</p>
  <button id="register">Register device</button><pre id="o3" hidden></pre></section>

<script>
const CLIENT_ID = "__CLIENT_ID__";
const API = "/v1";
const uuid = () => crypto.randomUUID();
const deviceId = uuid();
let nonce = "", token = "", verificationId = "";
const show = (id, v) => { const el = document.getElementById(id); el.hidden = false; el.textContent = typeof v === "string" ? v : JSON.stringify(v, null, 2); };
const on = id => document.getElementById(id).classList.remove("off");

async function call(path, body, auth) {
  const res = await fetch(API + path, {
    method: "POST",
    headers: {"Content-Type": "application/json", "Idempotency-Key": uuid(), "X-Client-Version": "android/0.0.1-web", ...(auth ? {Authorization: "Bearer " + auth} : {})},
    body: JSON.stringify(body),
  });
  const json = await res.json().catch(() => ({}));
  return {status: res.status, json};
}
// Tokens are not shown: only what a person would learn.
const safe = j => JSON.parse(JSON.stringify(j, (k, v) => /token/i.test(k) ? "(received)" : v));

async function boot() {
  const n = await call("/auth/nonce", {});
  nonce = n.json.data.nonce;
  const wait = () => window.google?.accounts?.id ? init() : setTimeout(wait, 100);
  const init = () => {
    google.accounts.id.initialize({client_id: CLIENT_ID, nonce, callback: async r => {
      const out = await call("/auth/social", {provider: "google", idToken: r.credential, nonce,
        device: {deviceId, platform: "android", model: "Browser test", osVersion: "web", appVersion: "0.0.1"}});
      show("o1", {httpStatus: out.status, ...safe(out.json)});
      if (out.status === 200 && out.json.data) { token = out.json.data.session.accessToken; if (out.json.data.next === "VERIFY_PHONE") on("s2"); else if (out.json.data.next === "REGISTER_DEVICE") on("s3"); }
    }});
    google.accounts.id.renderButton(document.getElementById("gbtn"), {theme: "outline", size: "large", text: "continue_with"});
  };
  wait();
}
document.getElementById("send").onclick = async () => {
  const out = await call("/auth/phone/start", {phone: document.getElementById("phone").value.trim()}, token);
  show("o2", {httpStatus: out.status, ...safe(out.json)});
  if (out.status === 200) { verificationId = out.json.data.verificationId; ["code", "verify"].forEach(i => document.getElementById(i).hidden = false); }
};
document.getElementById("verify").onclick = async () => {
  const out = await call("/auth/phone/verify", {verificationId, code: document.getElementById("code").value.trim()}, token);
  show("o2", {httpStatus: out.status, ...safe(out.json)});
  if (out.status === 200) on("s3");
};
document.getElementById("register").onclick = async () => {
  const pair = await crypto.subtle.generateKey({name: "ECDSA", namedCurve: "P-256"}, true, ["sign", "verify"]);
  const spki = new Uint8Array(await crypto.subtle.exportKey("spki", pair.publicKey));
  const out = await call("/devices", {deviceId, publicKey: btoa(String.fromCharCode(...spki)), keyAlgorithm: "ES256", model: "Browser test"}, token);
  show("o3", {httpStatus: out.status, ...safe(out.json)});
};
boot();
</script></body></html>
""".trimIndent()
