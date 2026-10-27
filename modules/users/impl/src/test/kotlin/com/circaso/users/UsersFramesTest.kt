package com.circaso.users

import com.circaso.shared.CircasoJson
import com.circaso.shared.RealtimeEnvelope
import com.circaso.shared.UserId
import com.circaso.shared.decodeData
import com.circaso.users.api.ConnectionAddedFrame
import com.circaso.users.api.ContactJoinedFrame
import com.circaso.users.api.ProfileUpdatedFrame
import com.circaso.users.data.cache.toEnvelope
import com.circaso.users.domain.ports.UsersFrame
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The wire shape of every `users` frame: one class per type, numbers as numbers (decision 2026-10-27). */
class UsersFramesTest {
    private val at = Instant.parse("2026-10-27T09:00:00Z")
    private val userId = UserId.new()

    private fun wire(frame: UsersFrame): Pair<RealtimeEnvelope, String> {
        val envelope = frame.toEnvelope(7, at)
        val json = CircasoJson.encodeToString(RealtimeEnvelope.serializer(), envelope)
        return CircasoJson.decodeFromString(RealtimeEnvelope.serializer(), json) to json
    }

    @Test
    fun `profile updated carries its version as a number`() {
        val (envelope, json) = wire(UsersFrame.ProfileUpdated(userId, 42))

        assertEquals("users", envelope.module)
        assertEquals("profile.updated", envelope.type)
        assertEquals(7, envelope.seq)
        assertEquals(ProfileUpdatedFrame(userId, 42), envelope.decodeData<ProfileUpdatedFrame>())
        assertTrue("\"version\":42" in json, json)
        assertTrue("\"userId\":\"$userId\"" in json, json)
    }

    @Test
    fun `contact joined and connection added carry the user and the name`() {
        val (joined, _) = wire(UsersFrame.ContactJoined(userId, "Amina"))
        val (added, _) = wire(UsersFrame.ConnectionAdded(userId, "Brian"))

        assertEquals("contact.joined", joined.type)
        assertEquals(ContactJoinedFrame(userId, "Amina"), joined.decodeData<ContactJoinedFrame>())
        assertEquals("connection.added", added.type)
        assertEquals(ConnectionAddedFrame(userId, "Brian"), added.decodeData<ConnectionAddedFrame>())
    }
}
