package com.circaso.gateway.realtime

import com.circaso.assistant.api.AssistantFrames
import com.circaso.assistant.api.AssistantStatusFrame
import com.circaso.calls.api.CallFrames
import com.circaso.calls.api.CallRingStoppedFrame
import com.circaso.calls.api.CallRingingFrame
import com.circaso.calls.api.CallSnapshot
import com.circaso.chama.api.ChamaFrames
import com.circaso.chama.api.ContributionAddedFrame
import com.circaso.chama.api.RequestUpdatedFrame
import com.circaso.identity.api.PushRegistrations
import com.circaso.integrations.api.PushGateway
import com.circaso.integrations.api.PushMessage
import com.circaso.integrations.api.PushPayload
import com.circaso.integrations.api.PushPlatform
import com.circaso.integrations.api.PushTarget
import com.circaso.integrations.api.VoipPushGateway
import com.circaso.integrations.api.VoipPushResult
import com.circaso.integrations.api.VoipRing
import com.circaso.integrations.api.VoipRingEnded
import com.circaso.messaging.api.MemberAddedFrame
import com.circaso.messaging.api.MessageNewFrame
import com.circaso.shared.Realtime
import com.circaso.shared.RealtimeEnvelope
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.decodeData
import com.circaso.spaces.api.InvitedFrame
import com.circaso.spaces.api.MembershipUpdatedFrame
import com.circaso.spaces.api.PostNewFrame
import com.circaso.spaces.api.PriceChangedFrame
import com.circaso.spaces.api.ReportResolvedFrame
import com.circaso.spaces.api.RequestNewFrame
import com.circaso.spaces.api.SpacesFrames
import com.circaso.status.api.MomentKeyRequestedFrame
import com.circaso.status.api.MomentViewedFrame
import com.circaso.status.api.StatusFrames
import com.circaso.support.api.SupportFrames
import com.circaso.support.api.TicketRepliedFrame
import com.circaso.users.api.ConnectionAddedFrame
import com.circaso.users.api.ContactJoinedFrame
import com.circaso.users.api.UsersFrames
import com.circaso.wallet.api.PaymentReceivedFrame
import com.circaso.wallet.api.SetupRequestedFrame
import com.circaso.wallet.api.TransferUpdatedFrame
import com.circaso.wallet.api.WalletFrames
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.KSerializer
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.circaso.gateway.realtime.GatewayRealtime")

/** How many offline users get their push at the same time when one frame goes to many. */
private const val PUSH_BATCH = 100

/** Messaging's pushes wake the app, which fetches and decrypts; this text shows only if it can't. */
private const val NEW_MESSAGE = "New message"

/** A support reply's push: from the team, as the app shows staff replies; a tap opens the ticket. */
private const val SUPPORT_TITLE = "Circaso Support"
private const val SUPPORT_TICKET_KIND = "support.ticket"

/** An incoming call is worth nothing after its 45-second ring, so the vendor may not hold it for longer. */
private const val RING_TTL_SECONDS = 45L

/**
 * What modules call to tell a user something now: to the socket when the user has one open, otherwise to push
 * when the frame is marked push-worthy. A frame that reaches neither is recovered by the module's replay when
 * the phone reconnects. An incoming call to an iPhone with a PushKit token rings through [voip] instead (decision
 * 2026-11-08), and through [push] only when that is off or fails.
 */
class GatewayRealtime(
    private val hub: SocketHub,
    private val push: PushGateway,
    private val registrations: PushRegistrations,
    private val voip: VoipPushGateway,
) : Realtime {
    override suspend fun deliver(userId: UserId, envelope: RealtimeEnvelope): Resource<Unit> {
        if (isRingStopped(envelope)) {
            hub.deliver(userId, envelope)
            stopIphoneRing(userId, envelope)
            return Resource.success(Unit)
        }
        if (hub.deliver(userId, envelope)) {
            ringIphoneWithSocket(userId, envelope)
            return Resource.success(Unit)
        }
        val message = pushFor(envelope, userId) ?: return Resource.success(Unit)
        pushTo(userId, message)
        return Resource.success(Unit)
    }

    override suspend fun deliverMany(userIds: Collection<UserId>, envelope: RealtimeEnvelope): Resource<Unit> {
        hub.deliverMany(userIds, envelope)
        if (isRingStopped(envelope)) {
            userIds.distinct().forEach { stopIphoneRing(it, envelope) }
            return Resource.success(Unit)
        }
        // Only users this frame would push to are looked up, so receipts and typing cost one publish and no reads.
        val candidates = userIds.distinct().mapNotNull { userId -> pushFor(envelope, userId)?.let { userId to it } }
        if (candidates.isEmpty()) return Resource.success(Unit)
        val offline = hub.withoutSocket(candidates.map { it.first }).toSet()
        val pushes = candidates.filter { (userId, _) -> userId in offline }
        candidates
            .filter { (userId, _) ->
                userId !in offline
            }.forEach { (userId, _) -> ringIphoneWithSocket(userId, envelope) }
        pushes.chunked(PUSH_BATCH).forEach { batch ->
            coroutineScope {
                batch.map { (userId, message) -> async { pushTo(userId, message) } }.awaitAll()
            }
        }
        return Resource.success(Unit)
    }

    private suspend fun pushTo(userId: UserId, message: PushMessage) {
        val target = registrations.pushTarget(userId) ?: return
        val platform = if (target.platform.equals("ios", ignoreCase = true)) PushPlatform.IOS else PushPlatform.ANDROID
        if (platform == PushPlatform.IOS && rangByVoip(userId, target.voipToken, message)) return
        val token = target.token ?: return
        val result = push.send(PushTarget(platform, token), message)
        if (result is Resource.Error) log.warn("push failed: code={}", result.error.code)
    }

    /**
     * A ring also goes as a VoIP push to an iPhone that still has a socket open (decision 2026-11-08): iOS suspends
     * an app seconds after it leaves the screen, but its socket can look open here until the ping times out, so the
     * frame alone would ring nothing. The app reports the push to CallKit and treats the frame for the same call as
     * the same ring. Android keeps ringing from the frame, and from FCM only when offline.
     */
    private suspend fun ringIphoneWithSocket(userId: UserId, envelope: RealtimeEnvelope) {
        if ("${envelope.module}.${envelope.type}" != "${CallFrames.MODULE}.${CallFrames.RINGING}" ||
            !voip.enabled
        ) {
            return
        }
        val message = pushFor(envelope, userId) ?: return
        val target = registrations.pushTarget(userId) ?: return
        if (target.platform.equals("ios", ignoreCase = true)) rangByVoip(userId, target.voipToken, message)
    }

    private fun isRingStopped(envelope: RealtimeEnvelope) =
        envelope.module == CallFrames.MODULE && envelope.type == CallFrames.RING_STOPPED

    /**
     * A ring that stopped (the caller hung up, the call ended, the ring ran out) goes to an iPhone with a PushKit token
     * as an "ended" VoIP push, socket or not (2026-11-09): its ring came by VoIP whatever its socket looked like (see
     * [ringIphoneWithSocket]), and a suspended app hears nothing else, so CallKit rang on after the caller had hung up.
     * Android stops from the frame or the FCM `call.ended`/`call.missed` that `call.ended` brings.
     */
    private suspend fun stopIphoneRing(userId: UserId, envelope: RealtimeEnvelope) {
        if (!voip.enabled) return
        val frame = decode(CallRingStoppedFrame.serializer(), envelope) ?: return
        val target = registrations.pushTarget(userId) ?: return
        if (!target.platform.equals("ios", ignoreCase = true)) return
        val token = target.voipToken ?: return
        val ended = VoipRingEnded(frame.callId.toString(), frame.chatId.toString(), frame.reason)
        if (voip.ended(token, ended) == VoipPushResult.TOKEN_INVALID) registrations.forgetVoipToken(userId, token)
    }

    /**
     * A ring to an iPhone goes as a PushKit VoIP push when it registered a token and APNs is configured; true when
     * APNs took it, so the FCM ring isn't sent too (the phone would ring twice). A token APNs refuses is forgotten;
     * either failure falls back to the FCM ring. Every other push, `call.missed` and `call.ended` included, is FCM's.
     */
    private suspend fun rangByVoip(
        userId: UserId,
        voipToken: String?,
        message: PushMessage,
    ): Boolean {
        if (voipToken == null || !voip.enabled) return false
        val ids = message.data
        if (ids.kind != RINGING_KIND) return false
        val ring =
            VoipRing(
                callId = ids.callId ?: return false,
                chatId = ids.chatId ?: return false,
                callKind = ids.callKind ?: return false,
                callerName = ids.callerName ?: return false,
            )
        return when (voip.ring(voipToken, ring)) {
            VoipPushResult.SENT -> {
                true
            }

            VoipPushResult.TOKEN_INVALID -> {
                registrations.forgetVoipToken(userId, voipToken)
                false
            }

            VoipPushResult.FAILED -> {
                false
            }
        }
    }

    /**
     * The frames worth a push when the phone is offline (Realtime and push → Events identity and users send;
     * Messaging → Socket frames; Wallet → Realtime frames). Messaging's pushes carry ids, never text from the chat;
     * the wallet's never carry an amount.
     */
    private fun pushFor(frame: RealtimeEnvelope, recipient: UserId): PushMessage? =
        when ("${frame.module}.${frame.type}") {
            "system.session.revoked" -> {
                PushMessage("Signed out", "Circaso was opened on another phone.", PushPayload(kind = "session.revoked"))
            }

            "${UsersFrames.MODULE}.${UsersFrames.CONTACT_JOINED}" -> {
                val joined = decode(ContactJoinedFrame.serializer(), frame)
                val name = joined?.displayName
                PushMessage(
                    "Circaso",
                    "${name ?: "Someone you know"} is on Circaso",
                    PushPayload(kind = "contact.joined", userId = joined?.userId?.toString()),
                )
            }

            "${UsersFrames.MODULE}.${UsersFrames.CONNECTION_ADDED}" -> {
                val added = decode(ConnectionAddedFrame.serializer(), frame)
                val name = added?.displayName
                PushMessage(
                    "Circaso",
                    "${name ?: "Someone"} connected with you",
                    PushPayload(kind = "connection.added", userId = added?.userId?.toString()),
                )
            }

            "messaging.${MessageNewFrame.TYPE}" -> {
                decode(MessageNewFrame.serializer(), frame)?.let(::newMessagePush)
            }

            "messaging.${MemberAddedFrame.TYPE}" -> {
                decode(MemberAddedFrame.serializer(), frame)?.let { addedToGroupPush(it, recipient) }
            }

            "${ChamaFrames.MODULE}.${ChamaFrames.CONTRIBUTION_ADDED}" -> {
                // to the other members; the contributor already knows. Data-only (2026-11-06): the phone names the group
                // (sealed, so never here) and the amount, in the person's currency, from the member's card in the chat.
                decode(ContributionAddedFrame.serializer(), frame)?.takeIf { it.userId != recipient }?.let {
                    val ids =
                        PushPayload(
                            kind = CONTRIBUTION_KIND,
                            chatId = it.chatId.toString(),
                            userId = it.userId.toString(),
                        )
                    PushMessage(it.goalName, "${it.displayName} added to ${it.goalName}", ids, dataOnly = true)
                }
            }

            "${ChamaFrames.MODULE}.${ChamaFrames.REQUEST_UPDATED}" -> {
                decode(RequestUpdatedFrame.serializer(), frame)?.let { requestPush(it, recipient) }
            }

            // A reaction added or changed on my moment (Pasaka, 2026-11-03): the module names the viewer only then. A
            // plain view or a cleared reaction never pushes; replies are chat messages and push as such.
            "${StatusFrames.MODULE}.${StatusFrames.VIEWED}" -> {
                decode(MomentViewedFrame.serializer(), frame)?.let(::reactionPush)
            }

            // Someone's new phone can't open my moments (2026-11-08): wake my phone to seal fresh copies for them, with
            // nothing shown. Without it the request waited for me to open Circaso, and their list stayed empty.
            "${StatusFrames.MODULE}.${StatusFrames.KEY_REQUESTED}" -> {
                decode(MomentKeyRequestedFrame.serializer(), frame)?.let(::keyRequestPush)
            }

            "${CallFrames.MODULE}.${CallFrames.RINGING}" -> {
                decode(CallRingingFrame.serializer(), frame)?.let(::ringingPush)
            }

            "${CallFrames.MODULE}.${CallFrames.ENDED}" -> {
                decode(CallSnapshot.serializer(), frame)?.let { endedCallPush(it, recipient) }
            }

            "${WalletFrames.MODULE}.${WalletFrames.TRANSFER_UPDATED}" -> {
                decode(TransferUpdatedFrame.serializer(), frame)?.let(::transferPush)
            }

            "${WalletFrames.MODULE}.${WalletFrames.PAYMENT_RECEIVED}" -> {
                val received = decode(PaymentReceivedFrame.serializer(), frame)
                val name = received?.fromName
                // A group wallet's money is pushed to its signer (decision 2026-11-03). The group's name is sealed, so
                // the server never has it: the text says "a group wallet". A space's name is no secret, so its push names it.
                val text =
                    when {
                        received?.spaceName != null && name != null -> "$name paid ${received.spaceName}"
                        received?.spaceName != null -> "${received.spaceName} received money"
                        received?.walletId != null && name != null -> "$name paid into a group wallet"
                        received?.walletId != null -> "A group wallet received money"
                        name != null -> "$name sent you money"
                        else -> "You received money"
                    }
                // The coins sound (decision 2026-10-31): iOS plays it from the app; Android gets the push as data and
                // plays it itself, or the usual sound when the person turned it off. No amount in a push, ever.
                PushMessage(
                    "Circaso",
                    text,
                    // Ids for the phone (2026-11-06): it reads the amount from the chat's card or the wallet's record and
                    // words it in the person's currency; a tap opens the chat at the card, or the payment's details.
                    PushPayload(
                        kind = PAYMENT_RECEIVED_KIND,
                        chatId = received?.chatId,
                        transferId = received?.transferId?.toString(),
                        transactionId = received?.transactionId,
                        walletId = received?.walletId?.toString(),
                    ),
                    dataOnly = true,
                    sound = MONEY_SOUND,
                )
            }

            // Asha is back after maintenance (decision 2026-11-07): once per person who met her down. No personal
            // data, so the same words show with Notification privacy on; `asha.back` opens Asha.
            "${AssistantFrames.MODULE}.${AssistantFrames.STATUS}" -> {
                decode(AssistantStatusFrame.serializer(), frame)?.takeIf { it.available }?.let {
                    PushMessage(ASHA_BACK_TITLE, ASHA_BACK_BODY, PushPayload(kind = ASHA_BACK_KIND))
                }
            }

            // Support replied on a ticket (decision 2026-11-08): the ticket's number and id only, never its text, which
            // may hold what the user wrote about their money. `ticket.updated` (a status change) is not worth a push.
            "${SupportFrames.MODULE}.${SupportFrames.TICKET_REPLIED}" -> {
                decode(TicketRepliedFrame.serializer(), frame)?.let {
                    PushMessage(
                        SUPPORT_TITLE,
                        "We replied to your ticket ${it.number}.",
                        PushPayload(kind = SUPPORT_TICKET_KIND, ticketId = it.ticketId.toString()),
                    )
                }
            }

            "${WalletFrames.MODULE}.${WalletFrames.SETUP_REQUESTED}" -> {
                val name = decode(SetupRequestedFrame.serializer(), frame)?.fromName ?: "Someone"
                PushMessage(
                    "Circaso",
                    "$name wants to send you money. Set up your wallet",
                    PushPayload(kind = "wallet.setup"),
                )
            }

            else -> {
                if (frame.module == SpacesFrames.MODULE) spacePush(frame, recipient) else null
            }
        }

    /**
     * Spaces' pushes (Spaces API → Pushes). Space content isn't end-to-end encrypted, so the text may name the space
     * and the people in it; a payment amount never shows. Each carries `kind` and `spaceId` for the tap.
     */
    private fun spacePush(frame: RealtimeEnvelope, recipient: UserId): PushMessage? =
        when (frame.type) {
            SpacesFrames.POST_NEW -> {
                decode(
                    PostNewFrame.serializer(),
                    frame,
                )?.takeIf { it.notify && it.post.author.userId != recipient }?.let {
                    // Lock-screen privacy (decision 2026-11-05): only a public space's push shows what was posted.
                    val text =
                        if (it.visibility == "PUBLIC") {
                            val what =
                                it.post.text?.take(POST_PREVIEW) ?: mediaWord(
                                    it.post.media
                                        .firstOrNull()
                                        ?.kind,
                                )
                            "${it.post.author.displayName}: $what"
                        } else {
                            "New post in ${it.spaceName}"
                        }
                    spaceMessage(it.spaceName, text, "space.post", it.spaceId.toString())
                }
            }

            SpacesFrames.INVITED -> {
                decode(InvitedFrame.serializer(), frame)?.let {
                    spaceMessage(
                        "Circaso",
                        "${it.inviterName} invited you to ${it.spaceName}",
                        "space.invited",
                        it.spaceId.toString(),
                    )
                }
            }

            SpacesFrames.REQUEST_NEW -> {
                decode(RequestNewFrame.serializer(), frame)?.let {
                    spaceMessage(
                        it.spaceName,
                        "${it.name} asked to join ${it.spaceName}",
                        "space.request",
                        it.spaceId.toString(),
                    )
                }
            }

            SpacesFrames.MEMBERSHIP_UPDATED -> {
                decode(MembershipUpdatedFrame.serializer(), frame)?.let(::membershipPush)
            }

            SpacesFrames.PRICE_CHANGED -> {
                decode(PriceChangedFrame.serializer(), frame)?.let {
                    val text = "${it.spaceName}'s price changes to ${it.price.amount} USDC from your next period"
                    spaceMessage(it.spaceName, text, "space.price", it.spaceId.toString())
                }
            }

            SpacesFrames.REPORT_RESOLVED -> {
                decode(ReportResolvedFrame.serializer(), frame)?.let {
                    val text = "Thanks for your report. ${it.spaceName}'s moderators looked at it"
                    spaceMessage("Circaso", text, "space.report", it.spaceId.toString())
                }
            }

            else -> {
                null
            }
        }

    private fun membershipPush(frame: MembershipUpdatedFrame): PushMessage? {
        val space = frame.spaceName
        val date = frame.until?.let(::shortDate)
        val (kind, text) =
            when (frame.reason) {
                "JOINED" -> {
                    "space.joined" to "You're in $space"
                }

                "RENEWED" -> {
                    "space.joined" to "Your $space membership is renewed"
                }

                "RENEWAL_DUE" -> {
                    "space.renewal" to
                        "Your $space membership ends${date?.let { " on $it" }.orEmpty()}. Renew"
                }

                "GRACE_STARTED" -> {
                    "space.grace" to
                        "Your $space membership ended. You can still take part${date?.let { " until $it" }.orEmpty()}"
                }

                "ENDED" -> {
                    "space.ended" to "Your $space membership has ended"
                }

                "ROLE_CHANGED" -> {
                    when (frame.role) {
                        "ADMIN" -> "space.role" to "You're now an admin of $space"
                        "MODERATOR" -> "space.role" to "You're now a moderator of $space"
                        else -> return null
                    }
                }

                "MUTED" -> {
                    "space.moderation" to "An admin muted you in $space"
                }

                "REMOVED" -> {
                    "space.moderation" to "You were removed from $space"
                }

                "BANNED" -> {
                    "space.moderation" to "You can't take part in $space any more"
                }

                else -> {
                    return null
                }
            }
        return spaceMessage(space, text, kind, frame.spaceId.toString())
    }

    private fun spaceMessage(
        title: String,
        body: String,
        kind: String,
        spaceId: String,
    ) = PushMessage(title, body, PushPayload(kind = kind, spaceId = spaceId))

    private fun mediaWord(kind: String?): String =
        when (kind) {
            "VIDEO" -> "Video"
            "VOICE" -> "Voice note"
            else -> "Photo"
        }

    /** "4 Dec", in UTC: the app words exact times itself. */
    private fun shortDate(iso: String): String? = runCatching { DATE.format(java.time.Instant.parse(iso)) }.getOrNull()

    /**
     * Only when the sender marked it `pushable`; a frame without the field wakes nobody (Messaging → Frame data), so
     * sender keys and control messages never do. Data-only (decision 2026-10-25): the app fetches the message from
     * the inbox, decrypts it and words the notification itself; the text here shows only if it can't.
     */
    private fun newMessagePush(frame: MessageNewFrame): PushMessage? {
        if (!frame.pushable) return null
        val ids = PushPayload(chatId = frame.chatId.toString(), messageId = frame.messageId.toString())
        return PushMessage("Circaso", NEW_MESSAGE, ids, dataOnly = true)
    }

    /**
     * Only the people added are woken (`userIds` in the frame, one frame per action, decision 2026-10-26); the other
     * members just see it next time.
     */
    private fun addedToGroupPush(frame: MemberAddedFrame, recipient: UserId): PushMessage? {
        if (recipient !in frame.userIds) return null
        // Never the group name, which is sealed: the push is data-only and the phone words it from the sealed name.
        val ids = PushPayload(chatId = frame.chatId.toString())
        return PushMessage("Circaso", "You were added to a group", ids, dataOnly = true)
    }

    /**
     * The sender hears only about the end of a payment (Wallet → Realtime frames); a push never shows an amount on
     * the lock screen.
     */
    // The admins still to decide hear of a pending request; the requester hears the outcome. Never an amount.
    // A group without a target has no goal name (decision 2026-11-03): the push says "the group wallet". The wallet's
    // signer hears when a payout is approved, since they are the one who pays it.
    private fun requestPush(frame: RequestUpdatedFrame, recipient: UserId): PushMessage? {
        val title = frame.goalName.ifBlank { "Circaso" }
        val from = frame.goalName.ifBlank { "the group wallet" }

        // Data-only with ids (2026-11-06): the phone names the group and the amount in the person's currency, and a
        // tap opens the group chat. `kind` says which side of the request this reader is on.
        fun push(state: String, body: String) =
            PushMessage(
                title,
                body,
                PushPayload(
                    kind = "$REQUEST_KIND.$state",
                    chatId = frame.chatId.toString(),
                    requestId = frame.requestId.toString(),
                ),
                dataOnly = true,
            )
        return when (frame.state) {
            "PENDING" -> {
                if (recipient in frame.waitingOn) {
                    push("pending", "${frame.requesterName} needs your approval to pay from $from")
                } else {
                    null
                }
            }

            "APPROVED" -> {
                when (recipient) {
                    frame.requestedBy -> push("approved", "Your request was approved")
                    frame.signerId -> push("payable", "A payout from $from was approved. It's ready for you to pay")
                    else -> null
                }
            }

            "DECLINED" -> {
                if (recipient == frame.requestedBy) push("declined", "Your request was declined") else null
            }

            "PAID" -> {
                if (recipient == frame.requestedBy) push("paid", "Your request was paid") else null
            }

            else -> {
                null
            }
        }
    }

    /** Only a frame that names the viewer, which status does when it adds or changes a reaction. */
    private fun reactionPush(frame: MomentViewedFrame): PushMessage? {
        val name = frame.viewerName ?: return null
        val reaction = frame.reaction ?: return null
        return PushMessage(
            "Circaso",
            "$name reacted $reaction to your moment",
            PushPayload(kind = MOMENT_LOVED_KIND),
        )
    }

    /**
     * Silent: the phone seals fresh copies of my moments for [MomentKeyRequestedFrame.requesterId] and sends them
     * (ST10), showing nothing. Lives an hour: after that the requester's phone has asked again or replay brings it.
     */
    private fun keyRequestPush(frame: MomentKeyRequestedFrame): PushMessage =
        PushMessage(
            "Circaso",
            "",
            PushPayload(kind = MOMENT_KEYS_REQUESTED_KIND, userId = frame.requesterId.toString()),
            silent = true,
            ttlSeconds = KEY_REQUEST_TTL_SECONDS,
        )

    /**
     * Data-only and high priority with a short life, so the app wakes and shows its incoming-call screen (Android's
     * full-screen intent, CallKit on iOS); the text shows only if it can't.
     */
    private fun ringingPush(frame: CallRingingFrame): PushMessage {
        val ids =
            PushPayload(
                chatId = frame.chatId.toString(),
                callId = frame.callId.toString(),
                kind = RINGING_KIND,
                callerName = frame.callerName,
                callKind = frame.kind,
            )
        val what = if (frame.kind == "VIDEO") "video call" else "call"
        return PushMessage(
            "Incoming $what",
            "${frame.callerName} is calling",
            ids,
            dataOnly = true,
            ttlSeconds = RING_TTL_SECONDS,
        )
    }

    /**
     * A call that ended while still ringing someone: nobody answered (`MISSED`, an alert "Missed call from …"), or
     * the caller hung up first (`CANCELLED`, data-only, so the phone stops ringing and words the missed call itself).
     * Nobody else is pushed when a call ends; whoever was in it had a socket.
     */
    private fun endedCallPush(frame: CallSnapshot, recipient: UserId): PushMessage? {
        if (recipient == frame.startedBy) return null
        val me = frame.participants.firstOrNull { it.userId == recipient } ?: return null
        if (me.state != "MISSED") return null
        val caller = frame.participants.firstOrNull { it.userId == frame.startedBy }?.displayName ?: "Someone"
        val text = "Missed call from $caller"
        val ids = PushPayload(chatId = frame.chatId.toString(), callId = frame.callId.toString())
        return when (frame.endReason) {
            // Data-only (2026-11-06): the phone words it itself, so Notification privacy can say just "Missed call".
            "MISSED" -> PushMessage("Missed call", text, ids.copy(kind = "call.missed"), dataOnly = true)

            "CANCELLED" -> PushMessage("Missed call", text, ids.copy(kind = "call.ended"), dataOnly = true)

            else -> null
        }
    }

    private fun transferPush(frame: TransferUpdatedFrame): PushMessage? =
        when (frame.state) {
            "CONFIRMED" -> PushMessage("Circaso", "Payment sent", transferIds(frame))
            "FAILED" -> PushMessage("Circaso", "Payment failed", transferIds(frame))
            else -> null
        }

    /** A tap on "Payment sent" or "Payment failed" opens that payment (2026-11-06). */
    private fun transferIds(frame: TransferUpdatedFrame) =
        PushPayload(
            kind = "transfer.updated",
            transferId = frame.transferId.toString(),
            walletId = frame.walletId?.toString(),
        )

    /** A frame's `data` as its class, from the module's `api`; null (no push) when it doesn't decode. */
    private fun <T> decode(serializer: KSerializer<T>, frame: RealtimeEnvelope): T? =
        runCatching { frame.decodeData(serializer) }.getOrNull()
}

/** An incoming call's push; to an iPhone with a PushKit token it goes through APNs as a VoIP push. */
private const val RINGING_KIND = "call.ringing"

/** The one push that says Asha is back after maintenance; the app opens Asha for any `asha*` kind. */
private const val ASHA_BACK_TITLE = "Asha is back"
private const val ASHA_BACK_BODY = "Ask me anything."
private const val ASHA_BACK_KIND = "asha.back"

/** What wakes the app for money received, and the sound it ships for it. */
private const val PAYMENT_RECEIVED_KIND = "payment.received"

/** A member put money into a group (chama `contribution.added`). */
private const val CONTRIBUTION_KIND = "chama.contribution"

/** A group payout request moved: `chama.request.pending|approved|payable|declined|paid`. */
private const val REQUEST_KIND = "chama.request"

/** A reaction on my moment (the kind is still named for the first, a heart): the app opens my moments. */
private const val MOMENT_LOVED_KIND = "moment.loved"

/** Someone's new phone asked for keys to my moments: a silent push, `userId` the requester. */
private const val MOMENT_KEYS_REQUESTED_KIND = "moment.keys_requested"
private const val KEY_REQUEST_TTL_SECONDS = 3_600L
private const val MONEY_SOUND = "money_received.wav"

/** A space post's push shows at most this much of its text. */
private const val POST_PREVIEW = 80

private val DATE: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter
        .ofPattern("d MMM", java.util.Locale.ENGLISH)
        .withZone(java.time.ZoneOffset.UTC)
