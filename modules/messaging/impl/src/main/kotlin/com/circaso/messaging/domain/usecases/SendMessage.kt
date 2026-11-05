package com.circaso.messaging.domain.usecases

import com.circaso.messaging.api.MessageKind
import com.circaso.messaging.api.SpacePrivateChats
import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.Chat
import com.circaso.messaging.domain.models.ChatKind
import com.circaso.messaging.domain.models.Fanout
import com.circaso.messaging.domain.models.GroupBody
import com.circaso.messaging.domain.models.InboxMessage
import com.circaso.messaging.domain.models.MessageRecord
import com.circaso.messaging.domain.models.SendMessageCommand
import com.circaso.messaging.domain.models.SentMessage
import com.circaso.messaging.domain.ports.PendingCounts
import com.circaso.messaging.domain.ports.Transactor
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.repositories.DeliveryRepository
import com.circaso.messaging.domain.support.DeliveryNotifier
import com.circaso.messaging.domain.support.Membership
import com.circaso.messaging.domain.support.MessagingConfig
import com.circaso.messaging.domain.support.OrderedIds
import com.circaso.shared.DomainError
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import com.circaso.users.api.ReachPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * M12 `POST /chats/{chatId}/messages`. A direct copy goes into the recipient's inbox with its ciphertext; a group
 * message is one `group_messages` body and a pointer per member, with `msg:pending:{messageId}` counting the members
 * still to ack (Messaging → Fan-out). The send answers once its transaction has committed; `message.new` frames and
 * pushes go out after, in the background.
 *
 * The `clientMessageId` claim, the message record, the body and every inbox row (one multi-row insert) are one
 * transaction. A retry that finds the claim returns the first send's id and writes nothing; a send that failed left
 * no claim and no rows, so its retry sends it once, under a new id. So a message keeps one id (decision 2026-10-27)
 * and no send answers before its rows exist. The pending counter is set for this attempt's id before the
 * transaction, so an ack never finds a pointer without it, and cleared again when the attempt didn't write.
 */
class SendMessage(
    private val chats: ChatRepository,
    private val membership: Membership,
    private val reach: ReachPolicy,
    private val delivery: DeliveryRepository,
    private val transactor: Transactor,
    private val pending: PendingCounts,
    private val notifier: DeliveryNotifier,
    private val ids: OrderedIds,
    private val background: CoroutineScope,
    private val config: MessagingConfig,
    private val clock: Clock,
    /**
     * Spaces' guard (Spaces phase 3): a private chat via a space closes once either side is banned from it. Null while
     * Spaces is archived: such a chat then follows the direct-chat rule.
     */
    private val spaceChats: SpacePrivateChats? = null,
) {
    suspend operator fun invoke(command: SendMessageCommand): Resource<SentMessage> {
        if (tooLarge(command)) return Resource.error(MessagingError.MessageTooLarge)
        val (chat, others) =
            when (val allowed = supervisorScope { authorize(command) }) {
                is Resource.Error -> return allowed
                is Resource.Success -> allowed.data
            }

        val messageId = ids.next()
        val sentAt = sentAt(messageId)
        val fanout = fanout(chat, command, others, messageId, sentAt)
        val counted = fanout.body != null && fanout.rows.isNotEmpty()
        if (counted) pending.start(messageId, fanout.rows.size)
        val earlier = commit(command, fanout, counted)
        if (earlier != null) {
            if (counted) pending.clear(messageId)
            return Resource.success(SentMessage(earlier, sentAt(earlier)))
        }
        background.launch { notify(command, fanout) }
        return Resource.success(SentMessage(messageId, sentAt))
    }

    /**
     * The send's one transaction: the claim, then the rows only if the claim was this attempt's. The earlier send's
     * id when there was one. It runs under `NonCancellable` (decision 2026-10-27): a caller that goes away mid-send
     * neither leaves the outcome unknown nor clears the counter of a message that committed. A failed transaction
     * clears this attempt's counter, which nothing would count down.
     */
    private suspend fun commit(
        command: SendMessageCommand,
        fanout: Fanout,
        counted: Boolean,
    ): UUID? {
        val messageId = fanout.record.messageId
        val expiresAt = clock.instant().plus(config.clientMessageTtl)
        return withContext(NonCancellable) {
            try {
                transactor.run("messages.send") {
                    val earlier =
                        delivery.claimClientMessage(command.sender, command.clientMessageId, messageId, expiresAt)
                    if (earlier == null) delivery.writeMessage(fanout)
                    earlier
                }
            } catch (e: Exception) {
                if (counted) pending.clear(messageId)
                throw e
            }
        }
    }

    /**
     * The chat and the other members, or the send's refusal. The chat row (a transaction), the members (the cache,
     * the rows on a miss) and, for a request shaped as a direct message by a member, reach both ways are read at
     * once rather than one after another; the answers are then judged in the order they always were, so the same
     * request gets the same error. A read whose answer isn't needed is cancelled, and its failure never surfaces,
     * as when it was never made: in a supervisor scope only an awaited read's failure is the send's.
     */
    private suspend fun CoroutineScope.authorize(command: SendMessageCommand): Resource<Pair<Chat, Set<UserId>>> {
        val members = async { membership.members(command.chatId) }
        val reach = async { directReach(command, members.await()) }
        val chat = chats.chat(command.chatId)
        if (chat == null) {
            members.cancel()
            reach.cancel()
            return Resource.error(MessagingError.ChatNotFound)
        }
        val all = members.await()
        if (command.sender !in all) {
            reach.cancel()
            return Resource.error(MessagingError.ChatNotFound)
        }
        val others = all - command.sender
        val problem = check(chat, command, others, reach)
        reach.cancel()
        return if (problem != null) Resource.error(problem) else Resource.success(chat to others)
    }

    /**
     * Reach both ways, read early, for a request a direct chat would take: no group ciphertext, not a sender key, one
     * recipient, and the sender and that recipient the chat's only members. Null for any other request. Whether the
     * chat is direct (and not a space's) is only known once its row is read; [canWrite] uses this answer only then.
     */
    private suspend fun directReach(command: SendMessageCommand, members: Set<UserId>): Boolean? {
        val shapedDirect = command.ciphertext == null && command.kind != MessageKind.SENDER_KEY
        val recipient = command.recipients.singleOrNull()?.userId ?: return null
        if (!shapedDirect || members != setOf(command.sender, recipient)) return null
        return reachableBothWays(command.sender, recipient)
    }

    private fun tooLarge(command: SendMessageCommand): Boolean {
        val texts = listOfNotNull(command.ciphertext) + command.recipients.map { it.ciphertext }
        return texts.any { decodedSize(it) > config.maxCiphertextBytes }
    }

    /** What the chat's kind asks of the request: who the copies are for, and where the ciphertext goes. */
    private suspend fun check(
        chat: Chat,
        command: SendMessageCommand,
        others: Set<UserId>,
        reach: Deferred<Boolean?>,
    ): DomainError? {
        val perRecipient = chat.kind == ChatKind.DIRECT || command.kind == MessageKind.SENDER_KEY
        if (!perRecipient) return groupCheck(command)
        if (command.ciphertext != null) return malformed("ciphertext", "goes in recipients for this message")
        if (!recipientsMatch(chat, command, others)) return MessagingError.RecipientsMismatch
        if (chat.kind == ChatKind.DIRECT && !canWrite(chat, command.sender, others.single(), reach)) {
            return MessagingError.NotReachable
        }
        return null
    }

    /** Direct: exactly the other person. `SENDER_KEY` in a group: some other members, each once. */
    private fun recipientsMatch(
        chat: Chat,
        command: SendMessageCommand,
        others: Set<UserId>,
    ): Boolean {
        val recipients = command.recipients.map { it.userId }
        val distinct = recipients.toSet()
        if (recipients.isEmpty() || recipients.size != distinct.size) return false
        if (chat.kind == ChatKind.DIRECT) return distinct == others
        return others.containsAll(distinct)
    }

    private fun groupCheck(command: SendMessageCommand) =
        when {
            command.ciphertext == null -> malformed("ciphertext", "is required for a group message")
            command.recipients.isNotEmpty() -> malformed("recipients", "only for SENDER_KEY in a group")
            else -> null
        }

    /** Both directions at once; the second is cancelled, and never fails the send, once the first says no. */
    private suspend fun reachableBothWays(sender: UserId, other: UserId): Boolean =
        bothTrue({ reach.canReach(sender, other) }, { reach.canReach(other, sender) })

    /**
     * A private chat via a space follows the space's rule (and blocks); any other direct chat, reach both ways, from
     * [early] when that read was made for this pair (`recipientsMatch` has checked that [other] is the recipient).
     * Without spaces' guard (Spaces archived 2026-11-05, `SPACES_ENABLED` off) a chat via a space is an ordinary one.
     */
    private suspend fun canWrite(
        chat: Chat,
        sender: UserId,
        other: UserId,
        early: Deferred<Boolean?>,
    ): Boolean {
        val via = chat.viaSpaceId
        val spaces = spaceChats
        if (via == null || spaces == null) return early.await() ?: reachableBothWays(sender, other)
        early.cancel()
        return bothTrue({ spaces.allowed(via, sender, other) }, { !reach.blockedEitherWay(sender, other) })
    }

    /** `first() && second()`, the two read at once. */
    private suspend fun bothTrue(first: suspend () -> Boolean, second: suspend () -> Boolean): Boolean =
        supervisorScope {
            val later = async { second() }
            if (!first()) {
                later.cancel()
                false
            } else {
                later.await()
            }
        }

    private fun fanout(
        chat: Chat,
        command: SendMessageCommand,
        others: Set<UserId>,
        messageId: UUID,
        sentAt: Instant,
    ): Fanout {
        fun row(to: UserId, ciphertext: String?) =
            InboxMessage(
                userId = to,
                messageId = messageId,
                chatId = chat.chatId,
                senderId = command.sender,
                kind = command.kind,
                ciphertext = ciphertext,
                groupBody = ciphertext == null,
                mediaIds = command.mediaIds,
                sentAt = sentAt,
            )
        val record = MessageRecord(messageId, chat.chatId, command.sender, command.kind, sentAt)
        val groupText = command.ciphertext
        if (groupText == null) return Fanout(record, command.recipients.map { row(it.userId, it.ciphertext) }, null)
        val body = GroupBody(chat.chatId, messageId, command.sender, groupText, sentAt)
        return Fanout(record, others.map { row(it, null) }, body)
    }

    /** Sender keys and control messages never wake anyone, whatever the phone asked. */
    private suspend fun notify(command: SendMessageCommand, fanout: Fanout) {
        val pushable = command.pushable && command.kind == MessageKind.MESSAGE
        val body = fanout.body
        if (body == null) {
            for (row in fanout.rows) notifier.newMessage(listOf(row.userId), row, pushable)
            return
        }
        val shared = fanout.rows.firstOrNull()?.copy(ciphertext = body.ciphertext) ?: return
        notifier.newMessage(fanout.rows.map { it.userId }, shared, pushable)
    }

    private fun sentAt(messageId: UUID): Instant = Uuid7.timestampOf(messageId) ?: clock.instant()

    private fun malformed(field: String, why: String) = SharedError.Malformed(field, why)
}

/** The bytes a base64 text decodes to, without decoding it. */
private fun decodedSize(base64: String): Int {
    val padding = base64.takeLast(2).count { it == '=' }
    return base64.length / 4 * 3 - padding
}
