package com.circaso.messaging.presentation.api

import com.circaso.messaging.presentation.dto.PhoneFrame
import com.circaso.messaging.presentation.mappers.parseChatId
import com.circaso.messaging.presentation.mappers.parseMessageId
import com.circaso.messaging.presentation.mappers.parseMessageIds
import com.circaso.messaging.presentation.services.PhoneFrameService
import com.circaso.shared.ClientFrame
import com.circaso.shared.DeviceId
import com.circaso.shared.RealtimeInbound
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import com.circaso.shared.decodeData
import com.circaso.shared.flatMap
import com.circaso.shared.map

/**
 * The phone's `messaging` frames, routed here by the socket hub (Messaging → Socket frames → Phone to server): `ack`,
 * `read` and `typing`, nothing that changes a chat (commands stay on HTTP). Each is decoded into its [PhoneFrame]
 * class. A frame the phone can't have meant (an unknown type, unreadable data) answers `request.malformed`, which the
 * hub only logs: the socket stays open.
 */
class MessagingRealtimeInbound(private val frames: PhoneFrameService, private val maxAckIds: Int) : RealtimeInbound {
    override suspend fun receive(
        userId: UserId,
        deviceId: DeviceId,
        frame: ClientFrame,
    ): Resource<Unit> =
        when (val phoneFrame = decode(frame)) {
            null -> Resource.error(SharedError.Malformed("data", "is not a messaging frame"))
            is PhoneFrame.Ack -> ack(userId, phoneFrame)
            is PhoneFrame.Read -> read(userId, phoneFrame)
            is PhoneFrame.Typing -> typing(userId, phoneFrame)
        }

    private fun decode(frame: ClientFrame): PhoneFrame? {
        val strategy = PhoneFrame.serializerFor(frame.type) ?: return null
        return runCatching { frame.decodeData(strategy) }.getOrNull()
    }

    private suspend fun ack(userId: UserId, frame: PhoneFrame.Ack): Resource<Unit> =
        parseMessageIds(frame.messageIds, maxAckIds).flatMap { frames.ack(userId, it) }.map { }

    private suspend fun read(userId: UserId, frame: PhoneFrame.Read): Resource<Unit> =
        parseChatId(frame.chatId).flatMap { chatId ->
            parseMessageId(frame.upToMessageId, "upToMessageId").flatMap { upTo ->
                frames.read(
                    userId,
                    chatId,
                    upTo,
                )
            }
        }

    private suspend fun typing(userId: UserId, frame: PhoneFrame.Typing): Resource<Unit> =
        parseChatId(frame.chatId).flatMap {
            frames.typing(
                userId,
                it,
                frame.on,
            )
        }
}
