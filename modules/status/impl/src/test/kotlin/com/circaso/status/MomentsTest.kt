package com.circaso.status

import com.circaso.shared.ErrorKind
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.status.api.MomentDeletedFrame
import com.circaso.status.api.MomentPostedFrame
import com.circaso.status.api.MomentViewedFrame
import com.circaso.status.domain.errors.StatusError
import com.circaso.status.domain.models.KeyCopy
import com.circaso.status.domain.models.ReactionChange
import com.circaso.status.domain.models.ReactionChange.Clear
import com.circaso.status.domain.models.ReactionChange.Keep
import com.circaso.status.domain.support.HEART
import com.circaso.status.domain.support.isReaction
import com.circaso.status.presentation.mappers.parseViewRequest
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ST3-ST7 against in-memory fakes: the audience rule, retries, limits, views and deletion. */
class MomentsTest {
    private val h = StatusHarness()
    private val poster = UserId.new()
    private val amina = UserId.new()
    private val baraka = UserId.new()
    private val chebet = UserId.new()

    @Test
    fun `a moment reaches the audience both ways reachable, and the others are dropped without a word`() =
        runTest {
            h.reach.cannot += baraka to poster // baraka doesn't have the poster saved
            h.reach.blocked += setOf(poster, chebet)

            val posted = h.post(h.command(poster, listOf(amina, baraka, chebet))).data()

            assertEquals(listOf(amina), h.moments.audienceOf(posted.momentId))
            assertEquals(listOf(MomentPostedFrame(posted.momentId, poster)), h.frames.to(amina))
            assertTrue(h.frames.to(baraka).isEmpty())
            assertTrue(h.frames.to(chebet).isEmpty())
            assertEquals(posted.postedAt.plus(Duration.ofHours(24)), posted.expiresAt)
        }

    @Test
    fun `a retry with the same clientMomentId answers the first moment and posts nothing`() =
        runTest {
            val command = h.command(poster, listOf(amina))
            val first = h.post(command).data()
            val again = h.post(command.copy(ciphertext = h.base64(10))).data()

            assertEquals(first, again)
            assertEquals(1, h.moments.moments.size)
            assertEquals(1, h.frames.to(amina).size)
        }

    @Test
    fun `the audience is 1 to 1,024 other people, and key copies are only for them`() =
        runTest {
            h.post(h.command(poster, emptyList())).assertError(StatusError.AudienceInvalid)
            h.post(h.command(poster, listOf(amina, poster))).assertError(StatusError.AudienceInvalid)
            h.post(h.command(poster, List(1_025) { UserId.new() })).assertError(StatusError.AudienceInvalid)
            h
                .post(h.command(poster, listOf(amina), keyCopies = listOf(KeyCopy(baraka, h.base64(8)))))
                .assertError(StatusError.AudienceInvalid)
            h
                .post(
                    h.command(
                        poster,
                        listOf(amina),
                        keyCopies = listOf(KeyCopy(amina, "AA=="), KeyCopy(amina, "AA==")),
                    ),
                ).assertError(StatusError.AudienceInvalid)
            assertTrue(h.moments.moments.isEmpty())
        }

    @Test
    fun `a body over 64 KiB is too large, and so is a moment with two files`() =
        runTest {
            h
                .post(h.command(poster, listOf(amina), ciphertext = h.base64(64 * 1_024 + 1)))
                .assertError(StatusError.MomentTooLarge)
            h.post(h.command(poster, listOf(amina), ciphertext = h.base64(64 * 1_024))).data()
            h
                .post(h.command(poster, listOf(amina), mediaIds = listOf(UUID.randomUUID(), UUID.randomUUID())))
                .assertError(StatusError.MediaInvalid)
        }

    @Test
    fun `the 51st moment in 24 hours is refused until the oldest leaves the window`() =
        runTest {
            repeat(50) {
                h.posted(poster, amina)
                h.clock.advance(Duration.ofMinutes(1))
            }

            val refused = h.post(h.command(poster, listOf(amina)))

            val error = assertIs<Resource.Error>(refused).error
            assertEquals("status.too_many_moments", error.code)
            assertEquals(ErrorKind.RATE_LIMITED, error.kind)
            assertEquals(Duration.ofHours(24).minusMinutes(50).seconds, error.details?.retryAfterSeconds)

            h.clock.advance(Duration.ofHours(24).minusMinutes(50))
            h.post(h.command(poster, listOf(amina))).data()
        }

    @Test
    fun `the audience lists the moment with its own key copy, and the poster sees it as theirs with the view count`() =
        runTest {
            val copy = h.base64(20)
            val posted =
                h.post(h.command(poster, listOf(amina, baraka), keyCopies = listOf(KeyCopy(amina, copy)))).data()
            h.view(amina, posted.momentId, Keep).data()

            val seen =
                h
                    .list(amina, null, 50)
                    .data()
                    .moments
                    .single()
            assertEquals(copy, seen.keyCopy)
            assertFalse(seen.mine)
            assertTrue(seen.viewed)
            assertNull(seen.viewCount)
            assertNull(
                h
                    .list(baraka, null, 50)
                    .data()
                    .moments
                    .single()
                    .keyCopy,
            )

            val own =
                h
                    .list(poster, null, 50)
                    .data()
                    .moments
                    .single()
            assertTrue(own.mine)
            assertEquals(1, own.viewCount)
            assertTrue(
                h
                    .list(chebet, null, 50)
                    .data()
                    .moments
                    .isEmpty(),
            )
        }

    @Test
    fun `the list pages by moment id and hides a poster blocked since posting`() =
        runTest {
            val other = UserId.new()
            val ids =
                (1..3).map { h.posted(poster, amina).momentId } + h.posted(other, amina).momentId +
                    h.posted(amina, other).momentId

            val first = h.list(amina, null, 2).data()
            assertEquals(ids.take(2), first.moments.map { it.moment.momentId })
            assertEquals(ids[1], first.next)
            val rest = h.list(amina, first.next, 10).data()
            assertEquals(ids.drop(2), rest.moments.map { it.moment.momentId })
            assertNull(rest.next)

            h.reach.blocked += setOf(amina, poster)
            assertEquals(
                ids.drop(3),
                h
                    .list(amina, null, 10)
                    .data()
                    .moments
                    .map { it.moment.momentId },
            )
        }

    @Test
    fun `a moment is gone from every read once 24 hours have passed`() =
        runTest {
            val posted = h.posted(poster, amina)
            h.clock.advance(Duration.ofHours(24))

            assertTrue(
                h
                    .list(amina, null, 50)
                    .data()
                    .moments
                    .isEmpty(),
            )
            assertTrue(
                h
                    .list(poster, null, 50)
                    .data()
                    .moments
                    .isEmpty(),
            )
            h.view(amina, posted.momentId, Keep).assertError(StatusError.MomentNotFound)
            h.views(poster, posted.momentId).assertError(StatusError.MomentNotFound)
        }

    @Test
    fun `the first view tells the poster, a repeat tells nobody, and a reaction change tells them again`() =
        runTest {
            h.profiles.names[amina] = "Amina"
            val posted = h.posted(poster, amina)

            val first = h.view(amina, posted.momentId, Keep).data()
            h.clock.advance(Duration.ofMinutes(5))
            h.view(amina, posted.momentId, Keep).data()
            h.view(amina, posted.momentId, Clear).data()
            val reacted = h.view(amina, posted.momentId, ReactionChange.Set(HEART)).data()

            assertEquals(first.viewedAt, reacted.viewedAt)
            assertEquals(
                listOf(
                    MomentViewedFrame(posted.momentId, amina, first.viewedAt, reacted = false),
                    MomentViewedFrame(
                        posted.momentId,
                        amina,
                        first.viewedAt,
                        reacted = true,
                        viewerName = "Amina",
                        reaction = HEART,
                    ),
                ),
                h.frames.to(poster),
            )
            val views = h.views(poster, posted.momentId).data()
            assertEquals(listOf(amina), views.map { it.userId })
            assertTrue(views.single().reacted)
            assertEquals(HEART, views.single().reaction)
        }

    @Test
    fun `a reaction is set, changed and cleared, telling the poster once each, and a new emoji names the viewer`() =
        runTest {
            h.profiles.names[amina] = "Amina"
            val posted = h.posted(poster, amina)
            val id = posted.momentId

            val first = h.view(amina, id, ReactionChange.Set("😂")).data()
            h.view(amina, id, ReactionChange.Set("😂")).data() // a retry: nothing new
            h.view(amina, id, Keep).data()
            h.view(amina, id, ReactionChange.Set("🔥")).data()
            h.view(amina, id, Clear).data()
            h.view(amina, id, Clear).data()

            fun frame(reaction: String?, name: String?) =
                MomentViewedFrame(id, amina, first.viewedAt, reaction != null, viewerName = name, reaction = reaction)
            assertEquals(
                listOf(frame("😂", "Amina"), frame("🔥", "Amina"), frame(null, null)),
                h.frames.to(poster),
            )
            val view = h.views(poster, id).data().single()
            assertNull(view.reaction)
            assertFalse(view.reacted)
        }

    @Test
    fun `a reaction must be one emoji, and a refused one records nothing`() =
        runTest {
            val posted = h.posted(poster, amina)

            for (bad in listOf("", "ok", "a😂", "😂 ", " ", "1", "\u200D", "\u0007", "😂😂😂😂😂")) {
                h.view(amina, posted.momentId, ReactionChange.Set(bad)).assertError(StatusError.ReactionInvalid)
            }
            assertNull(h.moments.member(posted.momentId, amina)?.viewedAt)
            assertTrue(h.frames.to(poster).isEmpty())

            for (good in listOf(HEART, "❤", "😂", "👍🏽", "🇰🇪", "1️⃣", "#⃣", "👩‍💻", "©️")) {
                assertTrue(isReaction(good), good)
            }
            assertFalse(isReaction("👨‍👩‍👧‍👦")) // 25 bytes: over the limit
            h.view(amina, posted.momentId, ReactionChange.Set("👍🏽")).data()
        }

    @Test
    fun `the viewer sees their own emoji, and the poster how many viewed and how many reacted`() =
        runTest {
            val posted = h.post(h.command(poster, listOf(amina, baraka, chebet))).data()
            h.view(amina, posted.momentId, ReactionChange.Set("😂")).data()
            h.view(baraka, posted.momentId, Keep).data()

            val seen =
                h
                    .list(amina, null, 50)
                    .data()
                    .moments
                    .single()
            assertEquals("😂", seen.reaction)
            assertTrue(seen.reacted)
            assertNull(seen.reactionCount)
            assertNull(
                h
                    .list(baraka, null, 50)
                    .data()
                    .moments
                    .single()
                    .reaction,
            )

            val own =
                h
                    .list(poster, null, 50)
                    .data()
                    .moments
                    .single()
            assertEquals(2, own.viewCount)
            assertEquals(1, own.reactionCount)
            assertNull(own.reaction)

            h.posted(poster, amina) // nobody has seen it yet
            val counts =
                h
                    .list(poster, null, 50)
                    .data()
                    .moments
                    .map { it.viewCount to it.reactionCount }
            assertEquals(listOf(2 to 1, 0 to 0), counts)
        }

    @Test
    fun `ST5's body sets, clears or keeps the reaction, and an older app's reacted still works`() {
        fun parsed(body: String) = parseViewRequest(body).data()

        assertEquals(Keep, parsed(""))
        assertEquals(Keep, parsed("{}"))
        assertEquals(Keep, parsed("""{"reacted":null}"""))
        assertEquals(ReactionChange.Set(HEART), parsed("""{"reacted":true}"""))
        assertEquals(Clear, parsed("""{"reacted":false}"""))
        assertEquals(ReactionChange.Set("😂"), parsed("""{"reaction":"😂"}"""))
        assertEquals(Clear, parsed("""{"reaction":null}"""))
        assertEquals(Clear, parsed("""{"reacted":true,"reaction":null}"""))
        assertEquals(ReactionChange.Set("🔥"), parsed("""{"reacted":false,"reaction":"🔥"}"""))
        for (bad in listOf("""{"reaction":5}""", """{"reaction":["😂"]}""", """{"reacted":"yes"}""", "[]", "{")) {
            assertEquals("request.malformed", assertIs<Resource.Error>(parseViewRequest(bad)).error.code, bad)
        }
    }

    @Test
    fun `only the audience can view, and a block since posting hides the moment`() =
        runTest {
            val posted = h.posted(poster, amina)

            h.view(baraka, posted.momentId, Keep).assertError(StatusError.MomentNotFound)
            h.view(poster, posted.momentId, Keep).assertError(StatusError.MomentNotFound)
            h.reach.blocked += setOf(amina, poster)
            h.view(amina, posted.momentId, Keep).assertError(StatusError.MomentNotFound)
        }

    @Test
    fun `views and deletion are the poster's, the audience is told not_poster and anyone else not_found`() =
        runTest {
            val posted = h.posted(poster, amina)

            h.views(amina, posted.momentId).assertError(StatusError.NotPoster)
            h.views(baraka, posted.momentId).assertError(StatusError.MomentNotFound)
            h.delete(amina, posted.momentId).assertError(StatusError.NotPoster)
            h.delete(baraka, posted.momentId).assertError(StatusError.MomentNotFound)
            assertTrue(h.moments.moments.containsKey(posted.momentId))
        }

    @Test
    fun `deleting a moment removes it with its views and file, and tells the audience`() =
        runTest {
            val mediaId = h.readyMedia(poster)
            val posted = h.post(h.command(poster, listOf(amina, baraka), mediaIds = listOf(mediaId))).data()
            h.view(amina, posted.momentId, ReactionChange.Set(HEART)).data()

            h.delete(poster, posted.momentId).data()

            assertTrue(h.moments.moments.isEmpty())
            assertTrue(h.moments.audience.isEmpty())
            assertFalse(h.storage.has(poster, mediaId))
            assertNull(h.media.find(mediaId))
            assertEquals(MomentDeletedFrame(posted.momentId, poster), h.frames.to(baraka).last())
            h.delete(poster, posted.momentId).assertError(StatusError.MomentNotFound)
        }

    @Test
    fun `a deleted account's moments, files and audience places go at once`() =
        runTest {
            val mediaId = h.readyMedia(poster)
            val unattached = h.readyMedia(poster)
            val posted = h.post(h.command(poster, listOf(amina), mediaIds = listOf(mediaId))).data()
            val theirs = h.posted(amina, poster)

            h.removeAccount(poster).data()
            h.removeAccount(poster).data()

            assertNull(h.moments.moments[posted.momentId])
            assertTrue(h.moments.audienceOf(theirs.momentId).isEmpty())
            assertNull(h.media.find(mediaId))
            assertNull(h.media.find(unattached))
            assertEquals(MomentDeletedFrame(posted.momentId, poster), h.frames.to(amina).last())
        }
}
