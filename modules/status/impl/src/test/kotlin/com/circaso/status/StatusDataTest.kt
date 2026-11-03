package com.circaso.status

import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import com.circaso.status.api.MomentId
import com.circaso.status.data.repositories.DatabaseTransactor
import com.circaso.status.data.repositories.MediaRepositoryImpl
import com.circaso.status.data.repositories.MomentRepositoryImpl
import com.circaso.status.data.repositories.StatusExpiry
import com.circaso.status.di.statusDatabaseSpec
import com.circaso.status.domain.errors.StatusError
import com.circaso.status.domain.models.AudienceMember
import com.circaso.status.domain.models.MediaKind
import com.circaso.status.domain.models.MediaState
import com.circaso.status.domain.models.Moment
import com.circaso.status.domain.models.MomentMedia
import com.circaso.status.domain.models.ReactionChange
import com.circaso.status.domain.models.ViewCounts
import com.circaso.status.domain.support.HEART
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `status` on PostgreSQL, migrated by its own Flyway scripts: the clientMomentId claim, what each reader sees, views,
 * the conditional attach, a rollback, and the sweep (expired moments take their audience with them).
 */
class StatusDataTest {
    private val database = TestPostgres.database(statusDatabaseSpec)
    private val moments = MomentRepositoryImpl(database)
    private val media = MediaRepositoryImpl(database)
    private val transactor = DatabaseTransactor(database)
    private val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
    private val poster = UserId.new()
    private val amina = UserId.new()
    private val baraka = UserId.new()

    @BeforeTest
    fun clean() = TestPostgres.truncate("status")

    private fun moment(
        postedAt: Instant = now,
        clientMomentId: UUID = UUID.randomUUID(),
        mediaId: UUID? = null,
    ) = Moment(
        momentId = MomentId.new(),
        posterId = poster,
        clientMomentId = clientMomentId,
        ciphertext = "AAAA",
        mediaId = mediaId,
        postedAt = postedAt,
        expiresAt = postedAt.plus(Duration.ofHours(24)),
    )

    private fun readyMedia(owner: UserId = poster) =
        MomentMedia(
            Uuid7.generate(),
            owner,
            MediaKind.IMAGE,
            1_024,
            MediaState.READY,
            null,
            now,
            now.plusSeconds(3_600),
        )

    @Test
    fun `a moment and its audience read back, and a second insert with the same clientMomentId writes nothing`() =
        runBlocking<Unit> {
            val first = moment()
            assertTrue(moments.insert(first, listOf(AudienceMember(amina, "a2V5"), AudienceMember(baraka, null))))
            assertFalse(
                moments.insert(moment(clientMomentId = first.clientMomentId), listOf(AudienceMember(amina, null))),
            )

            assertEquals(first, moments.findByClientId(poster, first.clientMomentId))
            assertEquals(first, moments.findLive(first.momentId, now))
            assertNull(moments.findLive(first.momentId, now.plus(Duration.ofHours(24))))
            assertEquals(setOf(amina, baraka), moments.audienceOf(first.momentId).toSet())
            assertEquals("a2V5", moments.member(first.momentId, amina)?.keyCopy)
            assertEquals(1 to now, moments.postedSince(poster, now.minusSeconds(1)))
        }

    @Test
    fun `a key request finds the posters showing the viewer something live, and a fresh copy replaces the old one`() =
        runBlocking<Unit> {
            val posted = moment()
            moments.insert(posted, listOf(AudienceMember(amina, "b2xk")))
            val other = UserId.new()

            assertEquals(setOf(poster), moments.postersShowing(amina, listOf(poster, other), now))
            assertTrue(moments.postersShowing(baraka, listOf(poster), now).isEmpty())
            assertTrue(moments.postersShowing(amina, listOf(poster), now.plus(Duration.ofHours(24))).isEmpty())
            assertTrue(moments.postersShowing(amina, emptyList(), now).isEmpty())

            assertTrue(moments.replaceKeyCopy(posted.momentId, amina, "bmV3"))
            assertEquals("bmV3", moments.member(posted.momentId, amina)?.keyCopy)
            assertFalse(moments.replaceKeyCopy(posted.momentId, baraka, "bmV3"))
            assertNull(moments.member(posted.momentId, baraka))
        }

    @Test
    fun `the audience lists what was shown to them and the poster their own, by moment id after the cursor`() =
        runBlocking<Unit> {
            val ids = (1..3).map { moment().also { moments.insert(it, listOf(AudienceMember(amina, null))) }.momentId }

            assertEquals(ids, moments.visibleTo(amina, null, now, 10).map { it.first.momentId })
            assertEquals(ids.drop(1), moments.visibleTo(amina, ids[0], now, 10).map { it.first.momentId })
            assertEquals(ids.take(2), moments.postedBy(poster, null, now, 2).map { it.momentId })
            assertTrue(moments.visibleTo(baraka, null, now, 10).isEmpty())
            assertTrue(moments.visibleTo(amina, null, now.plus(Duration.ofHours(24)), 10).isEmpty())
        }

    @Test
    fun `a view is stamped once, the reaction changes, and the poster counts it`() =
        runBlocking<Unit> {
            val posted = moment()
            moments.insert(posted, listOf(AudienceMember(amina, null), AudienceMember(baraka, null)))

            val first = moments.recordView(posted.momentId, amina, now, ReactionChange.Keep)
            val later = moments.recordView(posted.momentId, amina, now.plusSeconds(60), ReactionChange.Set(HEART))

            assertEquals(now, first?.viewedAt)
            assertNull(first?.reaction)
            assertEquals(now, later?.viewedAt)
            assertEquals(HEART, later?.reaction)
            assertNull(moments.recordView(posted.momentId, poster, now, ReactionChange.Keep))
            assertEquals(mapOf(posted.momentId to ViewCounts(1, 1)), moments.viewCounts(listOf(posted.momentId)))
            assertEquals(listOf(amina), moments.views(posted.momentId).map { it.userId })
        }

    @Test
    fun `a reaction is any emoji, kept by a plain view, changed, cleared, and counted with the views`() =
        runBlocking<Unit> {
            val posted = moment()
            val chebet = UserId.new()
            moments.insert(posted, listOf(amina, baraka, chebet).map { AudienceMember(it, null) })

            moments.recordView(posted.momentId, amina, now, ReactionChange.Set("😂"))
            assertEquals("😂", moments.recordView(posted.momentId, amina, now, ReactionChange.Keep)?.reaction)
            val changed = moments.recordView(posted.momentId, amina, now, ReactionChange.Set("🇰🇪"))
            assertEquals("🇰🇪", changed?.reaction)
            assertTrue(changed!!.reacted)
            moments.recordView(posted.momentId, baraka, now.plusSeconds(1), ReactionChange.Set("🔥"))
            moments.recordView(posted.momentId, chebet, now.plusSeconds(2), ReactionChange.Keep)

            assertEquals(mapOf(posted.momentId to ViewCounts(3, 2)), moments.viewCounts(listOf(posted.momentId)))
            assertEquals(listOf("🇰🇪", "🔥", null), moments.views(posted.momentId).map { it.reaction })

            val cleared = moments.recordView(posted.momentId, baraka, now, ReactionChange.Clear)
            assertNull(cleared?.reaction)
            assertFalse(cleared!!.reacted)
            assertEquals(mapOf(posted.momentId to ViewCounts(3, 1)), moments.viewCounts(listOf(posted.momentId)))
            assertEquals(
                listOf(true, false, false),
                moments.views(posted.momentId).map { it.reacted },
            )
            assertEquals(
                "🇰🇪",
                moments
                    .visibleTo(amina, null, now, 10)
                    .single()
                    .second.reaction,
            )
        }

    @Test
    fun `a file attaches once, only ready and the poster's, and a refused attach rolls the moment back`() =
        runBlocking<Unit> {
            val file = readyMedia()
            media.insert(file)
            val pending = readyMedia().copy(state = MediaState.PENDING)
            media.insert(pending)
            val first = moment(mediaId = file.mediaId)
            moments.insert(first, emptyList())

            assertFalse(media.attach(file.mediaId, amina, first.momentId, now, first.expiresAt))
            assertFalse(media.attach(pending.mediaId, poster, first.momentId, now, first.expiresAt))
            assertTrue(media.attach(file.mediaId, poster, first.momentId, now, first.expiresAt))
            assertFalse(media.attach(file.mediaId, poster, first.momentId, now, first.expiresAt))
            assertEquals(first.expiresAt, media.find(file.mediaId)?.expiresAt)

            val second = moment(mediaId = file.mediaId)
            val refused =
                transactor.attempt("test.post") {
                    moments.insert(second, listOf(AudienceMember(amina, null)))
                    if (media.attach(file.mediaId, poster, second.momentId, now, second.expiresAt)) {
                        Resource.success(Unit)
                    } else {
                        Resource.error(StatusError.MediaNotFound)
                    }
                }
            assertIs<Resource.Error>(refused)
            assertNull(moments.findLive(second.momentId, now))
        }

    @Test
    fun `deleting a moment takes its audience, and brings its file's expiry forward`() =
        runBlocking<Unit> {
            val file = readyMedia()
            media.insert(file)
            val posted = moment(mediaId = file.mediaId)
            moments.insert(posted, listOf(AudienceMember(amina, null)))
            media.attach(file.mediaId, poster, posted.momentId, now, posted.expiresAt)

            assertTrue(moments.delete(posted.momentId))
            assertEquals(listOf(now), media.expireFor(posted.momentId, now).map { it.expiresAt })

            assertTrue(moments.audienceOf(posted.momentId).isEmpty())
            assertEquals(listOf(file.mediaId), media.purgeable(now, 10).map { it.mediaId })
            assertTrue(media.delete(file.mediaId))
        }

    @Test
    fun `the sweep deletes expired moments with their audience and keeps live ones`() =
        runBlocking<Unit> {
            val expired = moment(postedAt = now.minus(Duration.ofHours(25)))
            val live = moment()
            moments.insert(expired, listOf(AudienceMember(amina, null)))
            moments.insert(live, listOf(AudienceMember(amina, null)))

            StatusExpiry(database).run()

            assertTrue(moments.allPostedBy(poster).map { it.momentId } == listOf(live.momentId))
            assertTrue(moments.audienceOf(expired.momentId).isEmpty())
            assertEquals(1, moments.leaveAudiences(amina))
        }
}
