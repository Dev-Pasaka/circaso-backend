package com.circaso.messaging

import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.messaging.api.ChatId
import com.circaso.messaging.data.repositories.GroupReportRepositoryImpl
import com.circaso.messaging.domain.usecases.CountGroups
import com.circaso.messaging.domain.usecases.GetGroupsByIds
import com.circaso.messaging.domain.usecases.ListGroupMembers
import com.circaso.messaging.domain.usecases.ListGroups
import com.circaso.messaging.domain.usecases.ListGroupsOf
import com.circaso.messaging.presentation.api.GroupReportsImpl
import com.circaso.messaging.presentation.services.GroupReportService
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Staff group reports on PostgreSQL (decision 2026-11-08): ids, counts and roles, newest first, by keyset. */
class GroupReportsTest {
    private val h = PostgresHarness()
    private val repository = GroupReportRepositoryImpl(h.database)
    private val reports =
        GroupReportsImpl(
            GroupReportService(
                ListGroups(repository),
                GetGroupsByIds(repository),
                ListGroupMembers(h.chats),
                ListGroupsOf(repository),
                CountGroups(repository),
            ),
        )

    @BeforeTest
    fun clean() = TestPostgres.truncate("messaging")

    private fun Resource<*>.errorCode(): String = (this as Resource.Error).error.code

    @Test
    fun `groups list newest first page by page, without direct chats, and are counted`() =
        runBlocking<Unit> {
            val owner = UserId.new()
            val groups = List(3) { h.group(owner, listOf(UserId.new())) }
            h.direct(owner, UserId.new())

            val first = reports.groups(null, 2).data()
            val second = reports.groups(first.nextCursor, 2).data()

            assertEquals(groups.reversed(), (first.items + second.items).map { it.chatId })
            assertEquals(2, first.items.size)
            assertNull(second.nextCursor)
            assertEquals(2, first.items.first().memberCount)
            assertEquals(owner, first.items.first().ownerId)
            assertEquals(3, reports.groupCount())
            assertEquals("request.malformed", reports.groups("nope", 10).errorCode())
        }

    @Test
    fun `members list the owner first, then by joining, with the role the chat gives them`() =
        runBlocking<Unit> {
            val owner = UserId.new()
            val (amina, brian) = List(2) { UserId.new() }
            val chatId = h.group(owner, listOf(amina))
            h.addAndJoin(owner, chatId, brian).data()

            val members = reports.members(chatId, 10)

            assertEquals(listOf(owner, amina, brian), members.map { it.userId })
            assertEquals(listOf("OWNER", "MEMBER", "MEMBER"), members.map { it.role })
            assertEquals(listOf(owner), reports.members(chatId, 1).map { it.userId })
            assertEquals(emptyList(), reports.members(h.direct(owner, amina), 10), "a direct chat is not a group")
        }

    @Test
    fun `a person's groups list newest first, and ids find only groups that exist`() =
        runBlocking<Unit> {
            val amina = UserId.new()
            val older = h.group(UserId.new(), listOf(amina))
            val newer = h.group(amina, listOf(UserId.new()))
            h.group(UserId.new(), listOf(UserId.new()))
            val direct = h.direct(amina, UserId.new())

            assertEquals(listOf(newer, older), reports.groupsOf(amina, 10).map { it.chatId })
            assertEquals(listOf(newer), reports.groupsOf(amina, 1).map { it.chatId })
            assertEquals(
                setOf(older, newer),
                reports.groupsByIds(setOf(older, newer, direct, ChatId(UUID.randomUUID()))).keys,
            )
        }
}
