package com.circaso.users

import com.circaso.identity.api.AccountStatus
import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.data.repositories.ContactRepositoryImpl
import com.circaso.users.data.repositories.ProfileRepositoryImpl
import com.circaso.users.data.repositories.ProfileSearchRepositoryImpl
import com.circaso.users.di.usersDatabaseSpec
import com.circaso.users.domain.models.Privacy
import com.circaso.users.domain.models.Profile
import com.circaso.users.domain.models.ProfileIdResults
import com.circaso.users.domain.support.ProfileViews
import com.circaso.users.domain.usecases.GetAdminProfiles
import com.circaso.users.domain.usecases.SearchProfilesByHandle
import com.circaso.users.domain.usecases.SearchProfilesByName
import com.circaso.users.presentation.api.ProfileAdminImpl
import com.circaso.users.presentation.services.ProfileAdminService
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Staff search on PostgreSQL (decision 2026-11-08): prefixes, keyset pages, wildcards, hidden profiles. */
class AdminSearchTest {
    private val database = TestPostgres.database(usersDatabaseSpec)
    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)
    private val profiles = ProfileRepositoryImpl(database, Clock.fixed(now, ZoneOffset.UTC))
    private val search = ProfileSearchRepositoryImpl(database)
    private val byHandle = SearchProfilesByHandle(search)
    private val byName = SearchProfilesByName(search)
    private val admin =
        ProfileAdminImpl(
            ProfileAdminService(
                GetAdminProfiles(profiles, ProfileViews(FakeAvatars(), ContactRepositoryImpl(database))),
                byHandle,
                byName,
            ),
        )

    @BeforeTest
    fun clean() = TestPostgres.truncate("users")

    private fun <T> Resource<T>.data(): T = (this as Resource.Success).data

    private fun Resource<*>.errorCode(): String = (this as Resource.Error).error.code

    private suspend fun profile(
        name: String,
        handle: String? = null,
        avatarKey: String? = null,
    ): UserId {
        val id = UserId.new()
        profiles.create(Profile(id, name, "about $name", handle, null, avatarKey, Privacy(), now))
        return id
    }

    /** Every page of a search, following the cursor to the end. */
    private suspend fun pages(search: suspend (String?) -> Resource<ProfileIdResults>): List<List<UserId>> {
        val pages = mutableListOf<List<UserId>>()
        var cursor: String? = null
        do {
            val page = search(cursor).data()
            pages += page.userIds
            cursor = page.nextCursor
        } while (cursor != null)
        return pages
    }

    @Test
    fun `a handle prefix finds live handles in handle order, page by page, hidden profiles too`() =
        runBlocking<Unit> {
            val amina = profile("Amina", "amina")
            val aminaK = profile("Amina K", "amina_k")
            val amani = profile("Amani", "amani")
            profile("Baraka", "baraka")
            profile("No handle")
            profiles.recordAccountStatus(amani, AccountStatus.SUSPENDED, now)

            val found = pages { byHandle(" @AM", it, 2) }

            assertEquals(listOf(listOf(amani, amina), listOf(aminaK)), found)
            assertEquals(emptyList(), byHandle("@", null, 10).data().userIds, "a blank prefix finds nothing")
        }

    @Test
    fun `wildcards in a prefix match themselves`() =
        runBlocking<Unit> {
            val underscore = profile("A", "a_b")
            profile("B", "axb")
            val percent = profile("C", "a%c")
            val backslash = profile("D", "a\\d")

            assertEquals(listOf(underscore), byHandle("a_", null, 10).data().userIds)
            assertEquals(listOf(percent), byHandle("a%", null, 10).data().userIds)
            assertEquals(listOf(backslash), byHandle("a\\", null, 10).data().userIds)
            assertEquals(emptyList(), byName("%", null, 10).data().userIds)
        }

    @Test
    fun `a name prefix is case-insensitive and pages through people who share a name`() =
        runBlocking<Unit> {
            val twins = listOf(profile("Amina"), profile("amina"), profile("AMINA")).sortedBy { it.value }
            val amani = profile("Amani Otieno")
            profile("Baraka")

            val found = pages { byName("am", it, 2) }

            assertEquals(listOf(amani) + twins, found.flatten())
            assertEquals(listOf(2, 2), found.map { it.size })
            assertEquals(twins, pages { byName("AMIN", it, 1) }.flatten())
        }

    @Test
    fun `a cursor that isn't one is refused`() =
        runBlocking<Unit> {
            assertEquals("request.malformed", byHandle("am", "garbage!", 10).errorCode())
            assertEquals("request.malformed", admin.searchByName("am", "bm90LWEtY3Vyc29y", 10).errorCode())
        }

    @Test
    fun `staff see every profile asked for, a suspended one included, with its photo`() =
        runBlocking<Unit> {
            val amina = profile("Amina", "amina", avatarKey = "avatars/amina.jpg")
            val hidden = profile("Hidden", "hidden")
            profiles.recordAccountStatus(hidden, AccountStatus.SUSPENDED, now)

            val seen = admin.profiles(setOf(amina, hidden, UserId.new()))

            assertEquals(setOf(amina, hidden), seen.keys)
            assertEquals("https://cdn.test/avatars/amina.jpg", seen.getValue(amina).avatarUrl)
            assertEquals("Hidden", seen.getValue(hidden).displayName)
            assertEquals("about Hidden", seen.getValue(hidden).about)
            assertNull(seen.getValue(hidden).avatarUrl)
        }
}
