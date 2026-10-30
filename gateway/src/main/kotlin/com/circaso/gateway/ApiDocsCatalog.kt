package com.circaso.gateway

import com.circaso.shared.CircasoJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal data class DocTag(val name: String, val description: String)

/** A sidebar section: several tags that belong together. */
internal data class DocGroup(val name: String, val tags: List<DocTag>)

/**
 * How the API reference is organised: **Infrastructure** first (it is where the reader opens), then the order in which
 * a person meets the API: sign in, verify the phone, register the device, then profile, people, Asha and the account.
 * A route lands in a section through the tag in its `docs(...)` call; the text here explains the section.
 */
internal object ApiDocsCatalog {
    val groups: List<DocGroup> =
        listOf(
            DocGroup(
                "Infrastructure",
                listOf(
                    DocTag(
                        "Health",
                        "Two routes for load balancers and monitoring. They need no token and answer with a " +
                            "small " +
                            "status body rather than the usual envelope.\n\n" +
                            "- **Live** says the process is running. An orchestrator restarts the process when " +
                            "it " +
                            "fails.\n" +
                            "- **Ready** says every connection works (PostgreSQL, Redis, the event streams). A " +
                            "load " +
                            "balancer stops sending traffic to an instance that is not ready, and the failing " +
                            "check " +
                            "is named in the body.",
                    ),
                    DocTag(
                        "Webhooks",
                        "Where outside services call Circaso back (SMS delivery receipts, email events, payment " +
                            "notifications). A vendor posts to `/v1/hooks/{vendor}` with no user token; instead " +
                            "the call " +
                            "must pass the vendor's own checks:\n\n" +
                            "1. a valid signature from that vendor,\n2. a timestamp inside a 5-minute window, so " +
                            "a " +
                            "captured request cannot be replayed later,\n" +
                            "3. a delivery id not seen before, so a repeated delivery is processed once,\n4. a " +
                            "source address on the vendor's allowlist, where the vendor publishes one.\n\n" +
                            "The answer is `200` straight away; the body is turned into a Circaso event " +
                            "afterwards, " +
                            "so a slow handler never makes the vendor retry. You will not call these yourself.",
                    ),
                ),
            ),
            DocGroup(
                "Authentication",
                listOf(
                    DocTag(
                        "Sign in",
                        "Everyone starts here. **There is no password**: Google or Apple proves who you are, and " +
                            "the " +
                            "same buttons both create an account and sign in.\n\n" +
                            "1. Ask for a **nonce** and include it in the Google or Apple request, so the token " +
                            "cannot be replayed.\n" +
                            "2. Send the ID token with the nonce and this phone's details. The answer says what " +
                            "to " +
                            "do next (`next`) and gives an `onboarding` token, or, if this phone is already " +
                            "registered and proves it with its key, a full session at once.\n" +
                            "3. Later, **refresh** the session before its 15-minute token ends, and **sign out** " +
                            "when the user asks. Signing out keeps the phone registered.\n\n" +
                            "This section also publishes the **public keys** that tokens are signed with, so a " +
                            "new " +
                            "key can be introduced without signing anyone out.",
                    ),
                    DocTag(
                        "Phone verification",
                        "A Circaso account has exactly one verified phone number, and one number belongs to " +
                            "exactly " +
                            "one account. Verify it once on sign-up, and again on any new phone.\n\n" +
                            "- **SMS code:** start a verification (a 6-digit code is texted), then send the " +
                            "code. " +
                            "Five wrong codes lock the account for 10 minutes. The text ends with the app's SMS " +
                            "Retriever hash so Android can read it for the user.\n" +
                            "- **Carrier check (Android):** a one-tap check that needs no SMS.\n" +
                            "- **Number already taken?** If a verified code proves the SIM belongs to you but " +
                            "the " +
                            "number is held by another account that never finished identity verification, you " +
                            "can " +
                            "ask for it to be **released**. The old account gets a 7-day notice, then the number " +
                            "is " +
                            "yours.\n\n" +
                            "All of these use the `onboarding` token.",
                    ),
                    DocTag(
                        "Register device",
                        "The last step of sign-up. The phone creates a key pair in its secure hardware (Android " +
                            "Keystore, Secure Enclave), keeps the private key, and sends the public key here. " +
                            "**This is " +
                            "the moment the account becomes active.**\n\n" +
                            "From then on the phone proves itself by signing requests with that key (sign-in on " +
                            "the " +
                            "same phone, and every token refresh), so a stolen Google account alone is not " +
                            "enough. " +
                            "Registering a new phone on an active account signs the old phone out.\n\n" +
                            "Production builds must also pass device attestation (Play Integrity on Android, App " +
                            "Attest on iOS); debug builds reach only staging, where it is logged and not " +
                            "required. " +
                            "The answer holds the `app` session, its refresh token, and the read-only `agent` " +
                            "token " +
                            "for Asha.",
                    ),
                ),
            ),
            DocGroup(
                "Your profile",
                listOf(
                    DocTag(
                        "Profile",
                        "What other people see of you: a name, a picture and a short \"about\" line. Phone " +
                            "numbers " +
                            "are never shown.\n\n" +
                            "- Read your own profile, including your privacy settings and the `version` of the " +
                            "profile.\n" +
                            "- Change it by sending only the fields that change **plus the `version` you last " +
                            "read**. If someone else (another device) changed it first you get " +
                            "`users.version_conflict`; reload and try again.\n" +
                            "- A picture is uploaded straight to storage: ask for a pre-signed URL, `PUT` the " +
                            "image " +
                            "to it, then confirm. The server never handles the image bytes. JPEG, PNG or WebP up " +
                            "to " +
                            "5 MB.\n" +
                            "- Privacy defaults: your picture is visible to anyone who can reach you, your about " +
                            "line to your contacts, and your @handle can be looked up.\n\n" +
                            "This section also has the public profile of someone you can reach.",
                    ),
                    DocTag(
                        "Handles",
                        "An `@handle` is how people find you without a phone number.\n\n" +
                            "- 3 to 20 characters: lowercase letters, digits and underscore, starting with a " +
                            "letter. " +
                            "Names like `circaso`, `support` and `asha` are reserved.\n" +
                            "- **Check** that a handle is free before claiming it; if it is taken you get a " +
                            "suggestion.\n" +
                            "- You can change it **once every 30 days**. The handle you give up is held for you " +
                            "for " +
                            "30 days, so nobody can take it the moment you leave it.\n" +
                            "- **Lookup** is an exact match, 30 an hour. A handle that does not exist, belongs " +
                            "to " +
                            "someone who turned lookup off, or belongs to someone who blocked you all answer the " +
                            "same way, on purpose.",
                    ),
                ),
            ),
            DocGroup(
                "People",
                listOf(
                    DocTag(
                        "Contacts",
                        "Who you can reach. You can message, call or pay someone only if they are an active " +
                            "account, " +
                            "neither of you has blocked the other, and **you** have a connection to them: their " +
                            "number " +
                            "is in your synced phone book, or you connected by QR code, invite link or @handle. " +
                            "They do " +
                            "not need to have you saved.\n\n" +
                            "- **Sync** uploads the phone book (up to 1,000 numbers per batch, changes only " +
                            "after " +
                            "the first full sync). Numbers on Circaso come back as contacts with public " +
                            "profiles. " +
                            "The rest are stored only as keyed hashes, so you hear when they join. Names from " +
                            "your " +
                            "address book stay on the phone.\n" +
                            "- **List** your contacts, a page at a time.\n" +
                            "- **Remove** a contact. Sync will not add them back while their number is still in " +
                            "your " +
                            "phone book. They are not told.",
                    ),
                    DocTag(
                        "Connections and invites",
                        "Meeting people without exchanging numbers.\n\n" +
                            "- **Invite:** create a link and QR payload (valid 7 days, revocable) and share it. " +
                            "Whoever opens it is connected to you **both ways**, because you handed it over on " +
                            "purpose.\n" +
                            "- **Connect** takes a typed @handle, a scanned QR code or an invite link. A handle " +
                            "connects **one way**: it lets the person who typed it reach the owner; the owner " +
                            "can " +
                            "reach back once they connect too.\n\n" +
                            "A block always wins, and the answer never says whether a block or a missing " +
                            "connection " +
                            "was the reason.",
                    ),
                    DocTag(
                        "Blocks and reach",
                        "A block overrides every other way of reaching someone, in both directions: neither of " +
                            "you " +
                            "can message, call or pay the other, and neither finds the other by @handle. Lifting " +
                            "a block " +
                            "restores the connection as it was.\n\n" +
                            "**Reach** answers one question: can I reach this person right now? It is for hints " +
                            "in " +
                            "the app; it never says why not.",
                    ),
                ),
            ),
            DocGroup(
                "Chats",
                listOf(
                    DocTag(
                        "Encryption keys",
                        "Chats are end-to-end encrypted on the phone, Signal-style; the server only stores and " +
                            "forwards bytes it cannot read. These routes are the public-key directory.\n\n" +
                            "- **Publish** this phone's identity key, signed prekey and up to 100 one-time prekeys. " +
                            "The upload must be signed by the phone's device key, so a stolen access token alone " +
                            "cannot swap anyone's keys.\n" +
                            "- **Key status** says how many one-time prekeys are left and when the signed prekey " +
                            "was set: top up below 20, rotate every 7 days.\n" +
                            "- **Fetch a bundle** to start a session with someone you can reach or share a group " +
                            "with. Each fetch uses up one one-time prekey; 60 bundles per hour.",
                    ),
                    DocTag(
                        "Chats and members",
                        "Direct chats (exactly two people, one chat per pair) and groups of up to 1,000 people " +
                            "with an owner, admins and members.\n\n" +
                            "- Opening a direct chat needs reach, as in People; opening it twice returns the same " +
                            "chat.\n" +
                            "- A group's name and avatar are encrypted on the phone under the group's metadata " +
                            "key; the server stores them sealed and never sees them.\n" +
                            "- Admins add and remove members (or anyone, if the group allows it); each person " +
                            "added must be reachable by whoever adds them. Changes use the `version` you last " +
                            "read.\n" +
                            "- Someone you can't reach is left out and listed in `notAdded`, without a reason.",
                    ),
                    DocTag(
                        "Messages and delivery",
                        "Sending encrypted messages and catching up on the ones waiting for you.\n\n" +
                            "- **Send** one ciphertext (up to 64 KB) with a `clientMessageId` made on the phone, " +
                            "so a retry returns the first result.\n" +
                            "- While the socket is open, messages arrive as `messaging.message.new` frames and " +
                            "acknowledgements, read receipts and typing go back over the socket. The **inbox** " +
                            "is for catching up and for push wakes: a push carries only ids, and the app fetches, " +
                            "decrypts and shows the notification itself.\n" +
                            "- A message is deleted from the server once it is acknowledged, and after 30 days " +
                            "at the latest. The sender can delete for everyone within 48 hours.\n" +
                            "- Read receipts and typing indicators can be turned off.",
                    ),
                    DocTag(
                        "Chat media",
                        "Photos, voice notes, video and files, Signal-style: the phone encrypts each file with " +
                            "its own AES-256-GCM key and uploads the ciphertext straight to storage through a " +
                            "pre-signed URL. The key and digest travel only inside the end-to-end encrypted " +
                            "message, so the server never sees what a file holds.\n\n" +
                            "- **Start** an upload (up to 32 MiB of ciphertext), PUT it, then **complete** it; " +
                            "the server checks the size.\n" +
                            "- Anyone currently in the chat can **download** it through a 15-minute link.\n" +
                            "- Files are deleted 30 days after the upload started, or after a day if never " +
                            "completed.",
                    ),
                    DocTag(
                        "Chat history",
                        "Your own copy of your chats, sealed on the phone under your history key, so a new " +
                            "phone can restore it. The server stores only ciphertext and only you can read or " +
                            "delete it.\n\n" +
                            "- **Append** sealed entries in batches and **read** a chat newest first; clear a chat " +
                            "or delete one message for yourself.\n" +
                            "- **Key backup** keeps the history key sealed under a passkey and/or a recovery key, " +
                            "for when the old phone is gone. Fetching it is limited to 5 times a day.",
                    ),
                    DocTag(
                        "Calls",
                        "Voice and video calls in a chat, up to 8 people at once. The phones talk WebRTC to " +
                            "Cloudflare Realtime's SFU; the server only brokers each connection's session " +
                            "descriptions and rings people.\n\n" +
                            "- **Start** a call in a chat (everyone else is rung: `calls/call.ringing`, pushed " +
                            "data-only to a phone without a socket), then **join** with the phone's offer and " +
                            "**pull** the others' tracks by user; the server maps each person to their session.\n" +
                            "- `calls/call.updated` follows every join, leave and mute; `calls/call.ended` the end. " +
                            "A ring nobody answers in 45 seconds is missed.\n" +
                            "- Media is encrypted in transit (DTLS-SRTP) but decrypted at the SFU, so a call is " +
                            "\"secure\", not end-to-end encrypted.",
                    ),
                ),
            ),
            DocGroup(
                "Money",
                listOf(
                    DocTag(
                        "Wallet",
                        "Your wallet holds stablecoins (USDC) in a Circle user-controlled wallet. **Circaso never " +
                            "holds your keys and never moves money on its own**: the server prepares, you sign with " +
                            "your Circle PIN in the Circle SDK on the phone, and the chain executes.\n\n" +
                            "- **Create** the wallet from the wallet section: you set a 6-digit PIN and security " +
                            "questions in the SDK, and the wallet is made on the default chain (Base).\n" +
                            "- **Several chains:** add Polygon, Ethereum or Solana from wallet management; each " +
                            "chain has its own address, and the PIN creates the wallet there.\n" +
                            "- **Balance** per chain and asset, added up over all chains, and optionally in your " +
                            "own currency (USDC counts as one US dollar; rates by Exchange Rate API).\n" +
                            "- **Paying:** a payment names its chain and leaves from your address there, to a " +
                            "person with a wallet on that chain or to an address valid on it; you sign it with the " +
                            "PIN, and `wallet` frames follow it. Amounts are strings (`amountMinor`, `amount`).\n" +
                            "- **Deleting your account** is refused while the wallet still holds money " +
                            "(`wallet.funds_remaining`).",
                    ),
                    DocTag(
                        "Chama",
                        "A group chat's shared goal. The money sits in the treasurer's own wallet: contributing is " +
                            "an ordinary payment to the treasurer from the chat, counted once it is confirmed. " +
                            "Paying " +
                            "someone from the group needs the admins' approval, then the treasurer pays and links " +
                            "the transfer.",
                    ),
                ),
            ),
            DocGroup(
                "Asha",
                listOf(
                    DocTag(
                        "Asha chat",
                        "Asha is Circaso's assistant. She appears as a pinned chat, by text or voice, and can " +
                            "also " +
                            "be asked a question from inside any chat.\n\n" +
                            "- **She can only read.** She looks things up (your contacts, and, through your " +
                            "phone, " +
                            "your own chats) and explains the app. She cannot send a message, move money or " +
                            "change " +
                            "anything.\n" +
                            "- **Your chats stay encrypted.** To read one, Asha asks your phone, which decrypts " +
                            "locally and returns a few matching snippets. They are used for that answer and " +
                            "never " +
                            "stored.\n" +
                            "- **One continuous conversation**, stored for you, which you can delete in part or " +
                            "whole at any time.\n" +
                            "- **A daily allowance** on the free plan; Asha Plus gives more.\n\n" +
                            "Replies stream as server-sent events (see the guide above). Talk to Asha with your " +
                            "**user** token; her own lookups use the read-only **agent** token you pass in " +
                            "`X-Agent-Token`.",
                    ),
                    DocTag(
                        "Asha voice and usage",
                        "Speak to Asha and hear her reply. A voice clip (up to 60 seconds) is turned into text " +
                            "and " +
                            "the audio is not kept; a reply can be read aloud. Voice has its own daily " +
                            "allowance, " +
                            "separate from text.\n\n" +
                            "**Live voice** is a WebSocket, `wss://…/v1/assistant/voice/live`, open only while " +
                            "talking hands-free: Opus in, Asha's voice back sentence by sentence, and talking over " +
                            "her stops her. Its frames are in the guide (Talking to Asha live); Swahili uses clips " +
                            "instead.\n\n" +
                            "**Usage** shows what you have used today, what is left, your plan and when the " +
                            "allowance resets.",
                    ),
                ),
            ),
            DocGroup(
                "Account",
                listOf(
                    DocTag(
                        "Account and security",
                        "Everything about keeping the account safe, behind the `app` token.\n\n" +
                            "- **Overview:** your masked number, the Google and Apple logins linked to you, this " +
                            "phone, and whether identity verification is done.\n" +
                            "- **Linked logins:** link Google or Apple as well, or unlink one **only while the " +
                            "other " +
                            "stays**.\n" +
                            "- **Security activity:** every sign-in, new phone and change, newest first.\n" +
                            "- **This phone:** store its push token, or forget it (the next sign-in will verify " +
                            "the " +
                            "number again).\n" +
                            "- **Delete the account:** it goes through only when nothing still needs it (for " +
                            "example, a wallet that still holds funds).",
                    ),
                    DocTag(
                        "Account recovery",
                        "There is no self-service recovery. The Google or Apple login **is** the account, so if " +
                            "it " +
                            "is lost only Circaso support can restore access, and only for an account that " +
                            "completed " +
                            "identity verification (an ID check and a live selfie).\n\n" +
                            "Open a request with the account's number and a contact email, then read its status " +
                            "as " +
                            "it moves from received, to checking, to a notice period, to restored. Support " +
                            "restores " +
                            "access only after a 48-hour notice to the old phone and email, which can cancel it. " +
                            "Uses the `onboarding` token of a new login.",
                    ),
                ),
            ),
            DocGroup(
                "Assistant tools",
                listOf(
                    DocTag(
                        "Agent routes",
                        "Read-only routes that Asha's own tools call with the user's **agent** token, exactly as " +
                            "an " +
                            "outside client would. They are the proof that she cannot change anything: the " +
                            "gateway " +
                            "refuses the agent token everywhere except `GET` under `/v1/agent/`, gives it its " +
                            "own rate " +
                            "limit, and these routes accept nothing else.\n\n" +
                            "You do not call these from the app; they are listed so you can see precisely what " +
                            "Asha " +
                            "is allowed to see.",
                    ),
                ),
            ),
        )

    val tagOrder: List<String> = groups.flatMap { g -> g.tags.map { it.name } }

    /** The spec Ktor generated, completed: the guide, the sections in order, security, servers, and routes sorted by journey. */
    fun complete(generated: String, guide: String): String {
        val doc = CircasoJson.parseToJsonElement(generated).jsonObject
        val info =
            (doc["info"] as? JsonObject).orEmpty().toMutableMap().apply {
                put("description", JsonPrimitive(guide))
                put(
                    "contact",
                    buildJsonObject {
                        put("name", "Circaso")
                        put("url", "https://circaso.com")
                    },
                )
            }
        val paths = sortedPaths(describedOnly((doc["paths"] as? JsonObject).orEmpty()))
        val components =
            ((doc["components"] as? JsonObject).orEmpty()).toMutableMap().apply {
                val schemes =
                    buildJsonObject {
                        putJsonObject("bearerAuth") {
                            put("type", "http")
                            put("scheme", "bearer")
                            put("bearerFormat", "JWT")
                            put(
                                "description",
                                "The access token from sign-in (`onboarding`) or device registration and refresh " +
                                    "(`app`, `agent`). Valid 15 minutes.",
                            )
                        }
                    }
                put("securitySchemes", schemes)
            }
        val result =
            doc.toMutableMap().apply {
                put("info", JsonObject(info))
                put(
                    "servers",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("url", "https://api.circaso.com")
                                put("description", "Production")
                            },
                        )
                        add(
                            buildJsonObject {
                                put("url", "http://localhost:8080")
                                put("description", "Local development")
                            },
                        )
                    },
                )
                put(
                    "tags",
                    buildJsonArray {
                        groups.flatMap { it.tags }.forEach { t ->
                            add(
                                buildJsonObject {
                                    put("name", t.name)
                                    put("description", t.description)
                                },
                            )
                        }
                    },
                )
                put(
                    "x-tagGroups",
                    buildJsonArray {
                        groups.forEach { g ->
                            add(
                                buildJsonObject {
                                    put("name", g.name)
                                    putJsonArray("tags") { g.tags.forEach { add(JsonPrimitive(it.name)) } }
                                },
                            )
                        }
                    },
                )
                put("paths", paths)
                put("components", JsonObject(components))
            }
        return CircasoJson.encodeToString(JsonObject.serializer(), JsonObject(result))
    }

    /** Only routes someone described, so nothing undocumented (or a library's) leaks into the reference by accident. */
    private fun describedOnly(paths: JsonObject): JsonObject =
        JsonObject(
            paths
                .mapNotNull { (path, item) ->
                    val operations = (item as JsonObject).filterValues { it !is JsonObject || it["tags"] != null }
                    if (operations.values.any { it is JsonObject }) path to JsonObject(operations) else null
                }.toMap(),
        )

    private val code = Regex("^([A-Z])(\\d+)")

    /**
     * Paths in the order a person uses them: by section, then by the I1, I2, U7 numbers in the summaries. Operations
     * that need a token get the bearer scheme (their notes say `Scope:`), the others none.
     */
    private fun sortedPaths(paths: JsonObject): JsonObject {
        fun operations(item: JsonObject) = item.filterValues { it is JsonObject }.mapValues { it.value.jsonObject }

        fun rank(item: JsonObject): Pair<Int, Int> {
            val op = operations(item).values.firstOrNull() ?: return Int.MAX_VALUE to 0
            val tag = (op["tags"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
            val summary = (op["summary"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val number =
                code
                    .find(summary)
                    ?.groupValues
                    ?.get(2)
                    ?.toIntOrNull() ?: 999
            return (tagOrder.indexOf(tag).takeIf { it >= 0 } ?: Int.MAX_VALUE) to number
        }
        val secured =
            paths.mapValues { (_, item) ->
                JsonObject(
                    (item as JsonObject).mapValues { (_, value) ->
                        if (value !is JsonObject) {
                            value
                        } else {
                            val description = (value["description"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                            val needsToken = "Scope: `" in description
                            JsonObject(
                                value +
                                    (
                                        "security" to
                                            if (needsToken) {
                                                buildJsonArray {
                                                    add(
                                                        buildJsonObject { putJsonArray("bearerAuth") {} },
                                                    )
                                                }
                                            } else {
                                                JsonArray(emptyList())
                                            }
                                    ),
                            )
                        }
                    },
                )
            }
        return JsonObject(
            secured.entries
                .sortedWith(
                    compareBy({
                        rank(it.value as JsonObject).first
                    }, { rank(it.value as JsonObject).second }, { it.key }),
                ).associate {
                    it.key to
                        it.value
                },
        )
    }
}

private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())

@Suppress("unused")
private val keep: JsonElement? = null
