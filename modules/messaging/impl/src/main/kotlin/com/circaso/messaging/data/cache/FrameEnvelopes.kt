package com.circaso.messaging.data.cache

import com.circaso.messaging.api.ChatUpdatedFrame
import com.circaso.messaging.api.KeysChangedFrame
import com.circaso.messaging.api.MemberAddedFrame
import com.circaso.messaging.api.MemberRemovedFrame
import com.circaso.messaging.api.MessageDeletedFrame
import com.circaso.messaging.api.MessageNewFrame
import com.circaso.messaging.api.MessageUndeliverableFrame
import com.circaso.messaging.api.MessagingFrame
import com.circaso.messaging.api.PresenceChangedFrame
import com.circaso.messaging.api.ReceiptFrame
import com.circaso.messaging.api.TypingFrame
import com.circaso.shared.RealtimeEnvelope
import java.time.Instant

/** Every frame `messaging` sends is in this module. */
private const val MODULE = "messaging"

/**
 * The envelope for a messaging frame, its `data` encoded with the frame's own class's serializer through
 * `RealtimeEnvelope.of`, so no `type` discriminator is added and the compiler checks that every frame type has a line
 * here.
 */
internal fun MessagingFrame.envelope(seq: Long, ts: Instant): RealtimeEnvelope =
    when (this) {
        is MessageNewFrame -> {
            RealtimeEnvelope.of(MODULE, type, seq, this, MessageNewFrame.serializer(), ts)
        }

        is ReceiptFrame -> {
            RealtimeEnvelope.of(MODULE, type, seq, this, ReceiptFrame.serializer(), ts)
        }

        is TypingFrame -> {
            RealtimeEnvelope.of(MODULE, type, seq, this, TypingFrame.serializer(), ts)
        }

        is MessageDeletedFrame -> {
            RealtimeEnvelope.of(MODULE, type, seq, this, MessageDeletedFrame.serializer(), ts)
        }

        is MessageUndeliverableFrame -> {
            RealtimeEnvelope.of(MODULE, type, seq, this, MessageUndeliverableFrame.serializer(), ts)
        }

        is KeysChangedFrame -> {
            RealtimeEnvelope.of(MODULE, type, seq, this, KeysChangedFrame.serializer(), ts)
        }

        is MemberAddedFrame -> {
            RealtimeEnvelope.of(MODULE, type, seq, this, MemberAddedFrame.serializer(), ts)
        }

        is MemberRemovedFrame -> {
            RealtimeEnvelope.of(MODULE, type, seq, this, MemberRemovedFrame.serializer(), ts)
        }

        is ChatUpdatedFrame -> {
            RealtimeEnvelope.of(MODULE, type, seq, this, ChatUpdatedFrame.serializer(), ts)
        }

        is PresenceChangedFrame -> {
            RealtimeEnvelope.of(MODULE, type, seq, this, PresenceChangedFrame.serializer(), ts)
        }
    }
