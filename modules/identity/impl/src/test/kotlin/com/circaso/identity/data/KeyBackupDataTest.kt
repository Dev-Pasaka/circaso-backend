package com.circaso.identity.data

import com.circaso.identity.api.AccountStatus
import com.circaso.identity.data.PostgresIdentity.Companion.count
import com.circaso.identity.domain.models.Account
import com.circaso.identity.domain.models.GroupKeyBackup
import com.circaso.identity.domain.models.GroupKeyPosition
import com.circaso.identity.domain.models.KeyBackupRoot
import com.circaso.identity.domain.models.SavedNames
import com.circaso.identity.domain.models.Verification
import com.circaso.identity.domain.repositories.Changes
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `identity.key_backup_roots` and `identity.group_key_backups` (V6) through the store: one root per account replaced
 * in place, group keys upserted per (chat, metaVersion), paged by key, and both deleted together.
 */
class KeyBackupDataTest {
    private val pg = PostgresIdentity()
    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)

    @BeforeTest
    fun clean() = PostgresIdentity.reset()

    private suspend fun account(): UserId {
        val userId = UserId.new()
        pg.transactor.run("test.create") {
            pg.accounts.create(Account(userId, null, AccountStatus.ACTIVE, null, Verification.NONE, 1, now))
        }
        return userId
    }

    private fun root(
        userId: UserId,
        version: Int,
        at: Instant,
    ) = KeyBackupRoot(
        userId,
        "AQID",
        "c2FsdHNhbHRzYWx0c2FsdA==",
        "cm9vdA$version",
        "prf-hkdf-sha256+aes-256-gcm/v1",
        version,
        at,
    )

    private fun key(
        userId: UserId,
        chatId: UUID,
        metaVersion: Int,
        ciphertext: String = "a2V5",
    ) = GroupKeyBackup(userId, chatId, metaVersion, ciphertext, now)

    private suspend fun write(changes: Changes) = pg.transactor.run("test.write") { pg.store.write(changes) }

    @Test
    fun `a root replace writes over the one row and keeps when it was first saved`() =
        runBlocking<Unit> {
            val userId = account()
            val other = account()
            write(Changes(keyBackupRoots = listOf(root(userId, 1, now), root(other, 1, now))))

            val later = now.plus(Duration.ofMinutes(5))
            write(Changes(keyBackupRoots = listOf(root(userId, 2, later))))

            assertEquals(root(userId, 2, later), pg.keyBackups.findRoot(userId))
            assertEquals(root(other, 1, now), pg.keyBackups.findRoot(other))
            assertEquals(
                1,
                count(
                    "SELECT count(*) FROM identity.key_backup_roots WHERE user_id = '$userId' AND created_at < updated_at",
                ),
            )
        }

    @Test
    fun `group keys upsert per chat and metaVersion, count and page in key order`() =
        runBlocking<Unit> {
            val userId = account()
            val other = account()
            val chats = List(3) { Uuid7.generate() }.sorted()
            write(Changes(groupKeys = chats.flatMap { chat -> (0..2).map { key(userId, chat, it) } }))
            write(Changes(groupKeys = listOf(key(other, chats[0], 0), key(userId, chats[1], 1, "bmV3"))))

            assertEquals(9, pg.keyBackups.countGroupKeys(userId))
            assertEquals(
                "bmV3",
                pg.keyBackups.findGroupKey(userId, chats[1], 1)?.ciphertext,
                "the second write replaced it",
            )
            assertTrue(pg.keyBackups.hasGroupKeys(userId, chats[2]))
            assertFalse(pg.keyBackups.hasGroupKeys(other, chats[2]))
            val first = pg.keyBackups.listGroupKeys(userId, null, 4)
            assertEquals(
                listOf(chats[0] to 0, chats[0] to 1, chats[0] to 2, chats[1] to 0),
                first.map {
                    it.chatId to
                        it.metaVersion
                },
            )
            val rest = pg.keyBackups.listGroupKeys(userId, GroupKeyPosition(chats[1], 0), 10)
            assertEquals(
                listOf(chats[1] to 1, chats[1] to 2, chats[2] to 0, chats[2] to 1, chats[2] to 2),
                rest.map {
                    it.chatId to
                        it.metaVersion
                },
            )
        }

    @Test
    fun `removing a backup deletes its root and every group key, and nobody else's`() =
        runBlocking<Unit> {
            val userId = account()
            val other = account()
            val chat = Uuid7.generate()
            write(
                Changes(
                    keyBackupRoots = listOf(root(userId, 1, now), root(other, 1, now)),
                    groupKeys = listOf(key(userId, chat, 0), key(userId, chat, 1), key(other, chat, 0)),
                    savedNames = listOf(SavedNames(userId, "bmFtZXM=", 1, now), SavedNames(other, "b3RoZXI=", 1, now)),
                ),
            )

            write(Changes(removedKeyBackups = listOf(userId)))
            write(Changes(removedKeyBackups = listOf(userId)))

            assertNull(pg.keyBackups.findRoot(userId))
            assertEquals(0, pg.keyBackups.countGroupKeys(userId))
            assertEquals(root(other, 1, now), pg.keyBackups.findRoot(other))
            assertEquals(1, pg.keyBackups.countGroupKeys(other))
            assertNull(pg.keyBackups.findSavedNames(userId))
            assertEquals(SavedNames(other, "b3RoZXI=", 1, now), pg.keyBackups.findSavedNames(other))
        }

    @Test
    fun `saved names are one blob per account, replaced in place with their version`() =
        runBlocking<Unit> {
            val userId = account()
            val big =
                java.util.Base64
                    .getEncoder()
                    .encodeToString(ByteArray(512 * 1024) { it.toByte() })
            val later = now.plusSeconds(5)

            assertNull(pg.keyBackups.findSavedNames(userId))
            write(Changes(savedNames = listOf(SavedNames(userId, "bmFtZXM=", 1, now))))
            write(Changes(savedNames = listOf(SavedNames(userId, big, 2, later))))

            assertEquals(SavedNames(userId, big, 2, later), pg.keyBackups.findSavedNames(userId))
            assertEquals(1, PostgresIdentity.count("SELECT count(*) FROM identity.saved_names"))
        }
}
