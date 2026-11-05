package com.circaso.messaging

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.ChatUpdatedFrame
import com.circaso.messaging.domain.models.Attachment
import com.circaso.messaging.domain.models.AttachmentKind
import com.circaso.messaging.domain.models.AttachmentState
import com.circaso.messaging.domain.models.AvatarChange
import com.circaso.messaging.domain.usecases.PurgeAttachments
import com.circaso.messaging.presentation.dto.UpdateGroupRequest
import com.circaso.messaging.presentation.mappers.toCommand
import com.circaso.messaging.presentation.mappers.toDto
import com.circaso.shared.CircasoJson
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** M8's group photo (decision 2026-11-05): who may set it, what it may be, and when the purge may take it. */
class GroupPhotoTest {
    private val amina = UserId.new()
    private val brian = UserId.new()

    private fun ChatHarness.photo(
        chatId: ChatId,
        kind: AttachmentKind = AttachmentKind.IMAGE,
        state: AttachmentState = AttachmentState.READY,
        sizeBytes: Long = 300_000,
    ): UUID {
        val now = clock.instant()
        val attachment =
            Attachment(
                Uuid7.generate(),
                chatId,
                amina,
                kind,
                sizeBytes,
                state,
                now,
                now.plus(config.attachmentRetention),
            )
        attachments.rows[attachment.attachmentId] = attachment
        return attachment.attachmentId
    }

    private fun ChatHarness.version(chatId: ChatId) = chats.chats.getValue(chatId).version

    private fun ChatHarness.purge() =
        PurgeAttachments(
            attachments,
            transactor,
            MemoryAttachmentStorage(),
            config,
            clock,
        )

    @Test
    fun `an admin makes a group's image its photo, kept past the 30 days and announced to members`() =
        runTest {
            val h = ChatHarness()
            val chatId = h.group(amina, listOf(brian)).chat.chatId
            val photo = h.photo(chatId)
            h.frames.sent.clear()

            val updated = h.update(amina, chatId, h.version(chatId), avatar = AvatarChange.Set(photo)).data()

            assertEquals(photo, updated.chat.avatarMediaId)
            assertEquals(photo.toString(), updated.toDto().avatarMediaId)
            assertNull(
                h.attachments.rows
                    .getValue(photo)
                    .expiresAt,
            )
            val frame = h.frames.sent.first { it.type == ChatUpdatedFrame.TYPE }
            assertEquals(photo, frame.data<ChatUpdatedFrame>().avatarMediaId)
            h.clock.advance(Duration.ofDays(40))
            assertEquals(0, h.purge()())
            assertNotNull(h.attachments.rows[photo])
        }

    @Test
    fun `the photo it replaces is purged only after the grace`() =
        runTest {
            val h = ChatHarness()
            val chatId = h.group(amina, listOf(brian)).chat.chatId
            val first = h.photo(chatId)
            h.update(amina, chatId, h.version(chatId), avatar = AvatarChange.Set(first)).data()
            h.clock.advance(Duration.ofDays(35))
            val second = h.photo(chatId)

            h.update(amina, chatId, h.version(chatId), avatar = AvatarChange.Set(second)).data()

            assertEquals(
                h.clock.instant().plus(h.config.groupPhotoGrace),
                h.attachments.rows
                    .getValue(first)
                    .expiresAt,
            )
            assertEquals(0, h.purge()(), "a member mid-download still gets the old one")
            h.clock.advance(h.config.groupPhotoGrace)
            assertEquals(1, h.purge()())
            assertNull(h.attachments.rows[first])
            assertNotNull(h.attachments.rows[second])
        }

    @Test
    fun `clearing the photo releases it, and setting the same one again changes nothing`() =
        runTest {
            val h = ChatHarness()
            val chatId = h.group(amina, listOf(brian)).chat.chatId
            val photo = h.photo(chatId)
            h.update(amina, chatId, h.version(chatId), avatar = AvatarChange.Set(photo)).data()
            h.update(amina, chatId, h.version(chatId), avatar = AvatarChange.Set(photo)).data()
            assertNull(
                h.attachments.rows
                    .getValue(photo)
                    .expiresAt,
            )

            val cleared = h.update(amina, chatId, h.version(chatId), avatar = AvatarChange.Remove).data()

            assertNull(cleared.chat.avatarMediaId)
            assertEquals(
                h.clock.instant().plus(h.config.groupPhotoGrace),
                h.attachments.rows
                    .getValue(photo)
                    .expiresAt,
            )
        }

    @Test
    fun `a change without a photo keeps the one the group has`() =
        runTest {
            val h = ChatHarness()
            val chatId = h.group(amina, listOf(brian)).chat.chatId
            val photo = h.photo(chatId)
            h.update(amina, chatId, h.version(chatId), avatar = AvatarChange.Set(photo)).data()

            val resealed = Base64.getEncoder().encodeToString(ByteArray(48) { 3 })
            val renamed = h.update(amina, chatId, h.version(chatId), sealedMeta = resealed).data()

            assertEquals(photo, renamed.chat.avatarMediaId)
            assertNull(
                h.attachments.rows
                    .getValue(photo)
                    .expiresAt,
            )
        }

    @Test
    fun `only an image of this group, completed, live and small enough can be the photo`() =
        runTest {
            val h = ChatHarness()
            val chatId = h.group(amina, listOf(brian)).chat.chatId
            val otherGroup = h.group(amina).chat.chatId
            val expired = h.photo(chatId)
            h.clock.advance(Duration.ofDays(31))
            val refused =
                listOf(
                    UUID.randomUUID(),
                    h.photo(otherGroup),
                    h.photo(chatId, kind = AttachmentKind.FILE),
                    h.photo(chatId, state = AttachmentState.PENDING),
                    h.photo(chatId, sizeBytes = h.config.maxGroupPhotoBytes + 1),
                    expired,
                )

            for (id in refused) {
                val result = h.update(amina, chatId, h.version(chatId), avatar = AvatarChange.Set(id))
                assertEquals("messaging.avatar_invalid", result.errorCode(), "attachment $id")
            }
            assertNull(
                h.chats.chats
                    .getValue(chatId)
                    .avatarMediaId,
            )
        }

    @Test
    fun `a member who isn't an admin can't set the photo`() =
        runTest {
            val h = ChatHarness()
            val chatId = h.group(amina, listOf(brian)).chat.chatId
            val photo = h.photo(chatId)

            val result = h.update(brian, chatId, h.version(chatId), avatar = AvatarChange.Set(photo))

            assertEquals("messaging.not_admin", result.errorCode())
            assertNotNull(
                h.attachments.rows
                    .getValue(photo)
                    .expiresAt,
            )
        }

    @Test
    fun `deleting the group lets the next purge take its photo`() =
        runTest {
            val h = ChatHarness()
            val chatId = h.group(amina).chat.chatId
            val photo = h.photo(chatId)
            h.update(amina, chatId, h.version(chatId), avatar = AvatarChange.Set(photo)).data()

            h.leaveGroup(amina, chatId).data()

            assertEquals(1, h.purge()())
            assertNull(h.attachments.rows[photo])
        }

    @Test
    fun `the request sets the photo with an id, clears it with null and leaves it when absent`() {
        val chatId = ChatId.new()
        val id = Uuid7.generate()

        fun avatar(json: String) =
            CircasoJson
                .decodeFromString(UpdateGroupRequest.serializer(), json)
                .toCommand(amina, chatId)

        assertEquals(AvatarChange.Set(id), avatar("""{"version":1,"avatarMediaId":"$id"}""").data().avatar)
        assertEquals(AvatarChange.Remove, avatar("""{"version":1,"avatarMediaId":null}""").data().avatar)
        assertNull(avatar("""{"version":1}""").data().avatar)
        assertEquals("request.malformed", avatar("""{"version":1,"avatarMediaId":"nope"}""").errorCode())
    }
}
