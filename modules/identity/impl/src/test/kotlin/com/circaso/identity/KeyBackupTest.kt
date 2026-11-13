package com.circaso.identity

import com.circaso.identity.api.Principal
import com.circaso.identity.domain.models.GroupKeyBackup
import com.circaso.identity.domain.models.SecurityEventType
import com.circaso.identity.presentation.controllers.keyBackupController
import com.circaso.shared.Uuid7
import com.circaso.web.CallPrincipal
import com.circaso.web.CallPrincipalKey
import com.circaso.web.installJson
import com.circaso.web.installStatusPages
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Group key backup (2026-11-12), KB1 to KB5 through their routes: one sealed root per account, versioned; group keys
 * upserted only for a chat the caller is (or was) in, capped and paged; turning it off wipes both.
 */
class KeyBackupTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val credentialId = "AQIDBAUGBwgJCgsMDQ4PEA"
    private val salt = base64(ByteArray(32) { it.toByte() })
    private val sealedRoot = base64(ByteArray(60) { (it * 7).toByte() })
    private val algorithm = "prf-hkdf-sha256+aes-256-gcm/v1"
    private val blob = base64(ByteArray(60) { (it * 3).toByte() })

    @Serializable
    private data class Envelope<T>(val code: String? = null, val data: T? = null)

    @Serializable
    private data class Root(
        val exists: Boolean,
        val credentialId: String? = null,
        val salt: String? = null,
        val sealedRoot: String? = null,
        val algorithm: String? = null,
        val version: Int? = null,
        val updatedAt: String? = null,
    )

    @Serializable
    private data class Key(val chatId: String, val metaVersion: Int, val ciphertext: String)

    @Serializable
    private data class Keys(val items: List<Key>, val nextCursor: String? = null)

    @Serializable
    private data class Names(
        val exists: Boolean,
        val version: Long,
        val ciphertext: String? = null,
        val updatedAt: String? = null,
    )

    @Serializable
    private data class NamesVersion(val version: Long)

    private class Setup(val h: Harness, val me: Principal)

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private suspend fun ApplicationTestBuilder.serve(): Setup {
        val h = Harness()
        val me = h.principalOf(h.signUp("amina", Phone(), "+254712345678").session.accessToken)
        application {
            installJson()
            installStatusPages()
            intercept(ApplicationCallPipeline.Plugins) {
                call.attributes.put(
                    CallPrincipalKey,
                    CallPrincipal(me.userId, me.deviceId, me.sessionId.value, me.scope),
                )
            }
            routing { route("/v1") { keyBackupController(h.keyBackupService) } }
        }
        return Setup(h, me)
    }

    private fun rootBody(
        credential: String = credentialId,
        saltValue: String = salt,
        sealed: String = sealedRoot,
        algo: String = algorithm,
        expectedVersion: Int? = null,
    ): String {
        val expected = expectedVersion?.let { ""","expectedVersion":$it""" }.orEmpty()
        return """{"credentialId":"$credential","salt":"$saltValue",""" +
            """"sealedRoot":"$sealed","algorithm":"$algo"$expected}"""
    }

    private suspend fun ApplicationTestBuilder.saveRoot(body: String = rootBody()): HttpResponse =
        client.put("/v1/key-backup/root") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.putKey(
        chatId: Any,
        metaVersion: Any,
        ciphertext: String = blob,
    ): HttpResponse =
        client.put("/v1/key-backup/group-keys/$chatId/$metaVersion") {
            contentType(ContentType.Application.Json)
            setBody("""{"ciphertext":"$ciphertext"}""")
        }

    private suspend fun ApplicationTestBuilder.putNames(ciphertext: String = blob, baseVersion: Long): HttpResponse =
        client.put("/v1/key-backup/saved-names") {
            contentType(ContentType.Application.Json)
            setBody("""{"ciphertext":"$ciphertext","baseVersion":$baseVersion}""")
        }

    private suspend fun ApplicationTestBuilder.names(): Names =
        json.decodeFromString<Envelope<Names>>(client.get("/v1/key-backup/saved-names").bodyAsText()).data!!

    private fun savedVersion(response: String) = json.decodeFromString<Envelope<NamesVersion>>(response).data!!.version

    private fun code(response: String) = json.decodeFromString<Envelope<Unit>>(response).code

    private fun root(response: String) = json.decodeFromString<Envelope<Root>>(response).data!!

    private fun keys(response: String) = json.decodeFromString<Envelope<Keys>>(response).data!!

    /** A chat the caller is in. */
    private fun Setup.group(): UUID = Uuid7.generate().also { h.memberships.members += it to me.userId }

    private fun Setup.logged() =
        h.store.log
            .filter { it.userId == me.userId && it.type.name.startsWith("KEY_BACKUP") }
            .map { it.type }

    @Test
    fun `with no root KB1 says exists false, and the first save is version 1, logged as on`() =
        testApplication {
            val s = serve()

            val before = client.get("/v1/key-backup/root")
            val saved = saveRoot()

            assertEquals(HttpStatusCode.OK, before.status)
            assertEquals(Root(exists = false), root(before.bodyAsText()))
            assertEquals(HttpStatusCode.OK, saved.status)
            val shown = root(client.get("/v1/key-backup/root").bodyAsText())
            assertEquals(Root(true, credentialId, salt, sealedRoot, algorithm, 1, shown.updatedAt), shown)
            assertNotNull(shown.updatedAt)
            assertEquals(root(saved.bodyAsText()), shown, "KB2 answers KB1's shape")
            assertEquals(listOf(SecurityEventType.KEY_BACKUP_ON), s.logged())
            assertEquals(
                "1",
                s.h.store.log
                    .last()
                    .detail,
            )
            assertEquals(
                "Pixel",
                s.h.store.log
                    .last()
                    .deviceModel,
            )
        }

    @Test
    fun `reading the root logs nothing`() =
        testApplication {
            val s = serve()
            saveRoot()

            repeat(5) { client.get("/v1/key-backup/root") }

            assertEquals(listOf(SecurityEventType.KEY_BACKUP_ON), s.logged())
        }

    @Test
    fun `each replace goes up one version and is logged as changed, and the expected version must match`() =
        testApplication {
            val s = serve()
            saveRoot()

            assertEquals(2, root(saveRoot(rootBody(sealed = base64(ByteArray(48)))).bodyAsText()).version)
            assertEquals(3, root(saveRoot(rootBody(expectedVersion = 2)).bodyAsText()).version)

            val stale = saveRoot(rootBody(sealed = base64(ByteArray(16)), expectedVersion = 2))
            assertEquals(HttpStatusCode.Conflict, stale.status)
            assertEquals("key_backup.version_conflict", code(stale.bodyAsText()))
            val kept = s.h.store.keyBackupRoots[s.me.userId]!!
            assertEquals(3, kept.version)
            assertEquals(sealedRoot, kept.sealedRoot, "the refused save changed nothing")
            assertEquals(
                listOf(
                    SecurityEventType.KEY_BACKUP_ON,
                    SecurityEventType.KEY_BACKUP_CHANGED,
                    SecurityEventType.KEY_BACKUP_CHANGED,
                ),
                s.logged(),
            )
        }

    @Test
    fun `an expected version with no root is a conflict too`() =
        testApplication {
            val s = serve()

            val response = saveRoot(rootBody(expectedVersion = 1))

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals("key_backup.version_conflict", code(response.bodyAsText()))
            assertNull(s.h.store.keyBackupRoots[s.me.userId])
        }

    @Test
    fun `a group key is an upsert, and the same blob again changes nothing`() =
        testApplication {
            val s = serve()
            saveRoot()
            val chat = s.group()

            val first = putKey(chat, 0)
            val stamped =
                s.h.store.groupKeys.values
                    .single()
                    .updatedAt
            val again = putKey(chat, 0)

            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals(HttpStatusCode.OK, again.status)
            assertEquals(
                stamped,
                s.h.store.groupKeys.values
                    .single()
                    .updatedAt,
                "the repeat wrote nothing",
            )
            val other = base64(ByteArray(40) { 9 })
            assertEquals(HttpStatusCode.OK, putKey(chat, 0, other).status)
            assertEquals(
                other,
                s.h.store.groupKeys.values
                    .single()
                    .ciphertext,
                "another blob replaces it",
            )
            assertEquals(HttpStatusCode.OK, putKey(chat, 1).status)
            assertEquals(2, s.h.store.groupKeys.size)
            assertTrue(s.logged().none { it != SecurityEventType.KEY_BACKUP_ON }, "group keys aren't logged")
        }

    @Test
    fun `a group key needs backup on`() =
        testApplication {
            val s = serve()
            val chat = s.group()

            val response = putKey(chat, 0)

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals("key_backup.not_found", code(response.bodyAsText()))
            assertTrue(
                s.h.store.groupKeys
                    .isEmpty(),
            )
        }

    @Test
    fun `a chat the caller isn't in is chat_not_found, unless they backed up one of its keys while in it`() =
        testApplication {
            val s = serve()
            saveRoot()
            val stranger = Uuid7.generate()
            val left = s.group()
            putKey(left, 0)
            s.h.memberships.members
                .clear()

            val refused = putKey(stranger, 0)
            val stillOk = putKey(left, 1)

            assertEquals(HttpStatusCode.NotFound, refused.status)
            assertEquals("key_backup.chat_not_found", code(refused.bodyAsText()))
            assertEquals(HttpStatusCode.OK, stillOk.status, "a key of a group they were in is still accepted")
            assertEquals(
                setOf(0, 1),
                s.h.store.groupKeys.values
                    .map { it.metaVersion }
                    .toSet(),
            )
        }

    @Test
    fun `at most 10,000 group keys, though replacing one at the cap still works`() =
        testApplication {
            val s = serve()
            saveRoot()
            val chat = s.group()
            repeat(10_000) {
                val id = Triple(s.me.userId, chat, it)
                s.h.store.groupKeys[id] = GroupKeyBackup(s.me.userId, chat, it, blob, Instant.now())
            }

            val over = putKey(chat, 10_000)
            val replace = putKey(chat, 7, base64(ByteArray(8)))

            assertEquals(HttpStatusCode.Conflict, over.status)
            assertEquals("key_backup.too_many", code(over.bodyAsText()))
            assertEquals(HttpStatusCode.OK, replace.status)
            assertEquals(10_000, s.h.store.groupKeys.size)
        }

    @Test
    fun `group keys page in chat then metaVersion order, by cursor`() =
        testApplication {
            val s = serve()
            saveRoot()
            val chats = List(3) { s.group() }.sorted()
            chats.forEach { chat -> repeat(3) { putKey(chat, 2 - it) } }

            val seen = mutableListOf<Key>()
            var cursor: String? = null
            var pages = 0
            do {
                val query = "limit=4" + (cursor?.let { "&cursor=$it" } ?: "")
                val page = keys(client.get("/v1/key-backup/group-keys?$query").bodyAsText())
                seen += page.items
                cursor = page.nextCursor
                pages++
            } while (cursor != null)

            assertEquals(3, pages)
            assertEquals(
                chats.flatMap { chat -> (0..2).map { chat.toString() to it } },
                seen.map {
                    it.chatId to
                        it.metaVersion
                },
            )
            assertTrue(seen.all { it.ciphertext == blob })
            val all = keys(client.get("/v1/key-backup/group-keys").bodyAsText())
            assertEquals(9, all.items.size)
            assertNull(all.nextCursor)
            val bad = client.get("/v1/key-backup/group-keys?cursor=nope")
            assertEquals(HttpStatusCode.BadRequest, bad.status)
            assertEquals("request.malformed", code(bad.bodyAsText()))
        }

    @Test
    fun `an empty backup lists no keys`() =
        testApplication {
            serve()

            assertEquals(Keys(emptyList()), keys(client.get("/v1/key-backup/group-keys").bodyAsText()))
        }

    @Test
    fun `turning backup off wipes the root and every group key, logs it once, and succeeds with nothing`() =
        testApplication {
            val s = serve()
            saveRoot()
            val chat = s.group()
            putKey(chat, 0)
            putKey(chat, 1)

            val first = client.delete("/v1/key-backup")
            val again = client.delete("/v1/key-backup")

            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals(HttpStatusCode.OK, again.status)
            assertNull(s.h.store.keyBackupRoots[s.me.userId])
            assertTrue(
                s.h.store.groupKeys
                    .isEmpty(),
            )
            assertEquals(1, s.logged().count { it == SecurityEventType.KEY_BACKUP_OFF })
            assertEquals(Root(exists = false), root(client.get("/v1/key-backup/root").bodyAsText()))
            assertEquals("key_backup.not_found", code(putKey(chat, 2).bodyAsText()), "a late upload doesn't land")
            assertEquals(1, root(saveRoot().bodyAsText()).version, "a root saved after turning off starts again at 1")
            assertEquals(SecurityEventType.KEY_BACKUP_ON, s.logged().last())
        }

    @Test
    fun `each malformed root field has its own code, and nothing is stored`() =
        testApplication {
            val s = serve()
            val cases =
                listOf(
                    rootBody(credential = "") to "key_backup.credential_id_invalid",
                    rootBody(credential = "not base64url!") to "key_backup.credential_id_invalid",
                    rootBody(credential = "A".repeat(1025)) to "key_backup.credential_id_invalid",
                    rootBody(saltValue = base64(ByteArray(15))) to "key_backup.salt_invalid",
                    rootBody(saltValue = base64(ByteArray(65))) to "key_backup.salt_invalid",
                    rootBody(saltValue = "%%%") to "key_backup.salt_invalid",
                    rootBody(sealed = "") to "key_backup.sealed_root_invalid",
                    rootBody(sealed = base64(ByteArray(1025))) to "key_backup.sealed_root_invalid",
                    rootBody(sealed = "not-base64*") to "key_backup.sealed_root_invalid",
                    rootBody(algo = "") to "key_backup.algorithm_invalid",
                    rootBody(algo = "a".repeat(65)) to "key_backup.algorithm_invalid",
                    rootBody(algo = "aes gcm") to "key_backup.algorithm_invalid",
                    rootBody(expectedVersion = 0) to "key_backup.expected_version_invalid",
                    """{"credentialId":"$credentialId","salt":"$salt"}""" to "request.malformed",
                )

            for ((request, expected) in cases) {
                val response = saveRoot(request)
                assertEquals(HttpStatusCode.BadRequest, response.status, request.take(120))
                assertEquals(expected, code(response.bodyAsText()), request.take(120))
            }
            assertNull(s.h.store.keyBackupRoots[s.me.userId])
            assertEquals(
                HttpStatusCode.OK,
                saveRoot(rootBody(sealed = base64(ByteArray(1024)))).status,
                "1 KiB is allowed",
            )
        }

    @Test
    fun `each malformed group key part has its own code, checked before membership`() =
        testApplication {
            val s = serve()
            saveRoot()
            val chat = s.group()
            val cases =
                listOf(
                    putKey("not-a-uuid", 0) to "key_backup.chat_id_invalid",
                    putKey(chat, -1) to "key_backup.meta_version_invalid",
                    putKey(chat, "x") to "key_backup.meta_version_invalid",
                    putKey(chat, "99999999999") to "key_backup.meta_version_invalid",
                    putKey(chat, 0, "") to "key_backup.ciphertext_invalid",
                    putKey(chat, 0, "not-base64*") to "key_backup.ciphertext_invalid",
                    putKey(chat, 0, base64(ByteArray(513))) to "key_backup.ciphertext_invalid",
                )

            for ((response, expected) in cases) {
                assertEquals(HttpStatusCode.BadRequest, response.status, expected)
                assertEquals(expected, code(response.bodyAsText()), expected)
            }
            assertEquals(0, s.h.memberships.asked, "a malformed request never reaches messaging")
            assertTrue(
                s.h.store.groupKeys
                    .isEmpty(),
            )
            assertEquals(HttpStatusCode.OK, putKey(chat, 0, base64(ByteArray(512))).status, "512 bytes is allowed")
            assertFalse(
                s.h.store.groupKeys
                    .isEmpty(),
            )
        }

    @Test
    fun `with no saved names KB6 says exists false at version 0, with nulls`() =
        testApplication {
            serve()

            val response = client.get("/v1/key-backup/saved-names")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(Names(exists = false, version = 0), names())
            val raw = response.bodyAsText()
            assertTrue(raw.contains(""""ciphertext":null""") && raw.contains(""""updatedAt":null"""), raw)
        }

    @Test
    fun `saved names save from 0, then each save goes up one, and KB6 gives the blob back as sent`() =
        testApplication {
            val s = serve()
            saveRoot()

            val first = putNames(baseVersion = 0)
            val other = base64(ByteArray(100) { 5 })
            val second = putNames(other, baseVersion = 1)

            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals(1, savedVersion(first.bodyAsText()))
            assertEquals(2, savedVersion(second.bodyAsText()))
            val shown = names()
            assertEquals(Names(true, 2, other, shown.updatedAt), shown)
            assertNotNull(shown.updatedAt)
            assertEquals(listOf(SecurityEventType.KEY_BACKUP_ON), s.logged(), "saved names aren't logged")
        }

    @Test
    fun `a stale or ahead baseVersion is a conflict and changes nothing`() =
        testApplication {
            val s = serve()
            saveRoot()
            putNames(baseVersion = 0)
            putNames(baseVersion = 1)

            for (base in listOf(0L, 1L, 3L)) {
                val response = putNames(base64(ByteArray(8)), baseVersion = base)
                assertEquals(HttpStatusCode.Conflict, response.status, "base $base")
                assertEquals("identity.saved_names_conflict", code(response.bodyAsText()))
            }
            val kept = s.h.store.savedNames[s.me.userId]!!
            assertEquals(2, kept.version)
            assertEquals(blob, kept.ciphertext)
        }

    @Test
    fun `saved names need key backup on`() =
        testApplication {
            val s = serve()

            val response = putNames(baseVersion = 0)

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals("identity.key_backup_off", code(response.bodyAsText()))
            assertNull(s.h.store.savedNames[s.me.userId])
        }

    @Test
    fun `malformed saved names are refused before anything is read, and 512 KiB is allowed`() =
        testApplication {
            val s = serve()
            saveRoot()
            val cases =
                listOf(
                    putNames("", 0) to "identity.saved_names_ciphertext_invalid",
                    putNames("not-base64*", 0) to "identity.saved_names_ciphertext_invalid",
                    putNames(base64(ByteArray(512 * 1024 + 1)), 0) to "identity.saved_names_ciphertext_invalid",
                    putNames(blob, -1) to "identity.saved_names_base_version_invalid",
                )

            for ((response, expected) in cases) {
                assertEquals(HttpStatusCode.BadRequest, response.status, expected)
                assertEquals(expected, code(response.bodyAsText()), expected)
            }
            val missing =
                client.put("/v1/key-backup/saved-names") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"ciphertext":"$blob"}""")
                }
            assertEquals(HttpStatusCode.BadRequest, missing.status)
            assertEquals("request.malformed", code(missing.bodyAsText()))
            assertNull(s.h.store.savedNames[s.me.userId])
            val max = putNames(base64(ByteArray(512 * 1024)), 0)
            assertEquals(HttpStatusCode.OK, max.status)
            assertEquals(1, savedVersion(max.bodyAsText()))
        }

    @Test
    fun `turning key backup off deletes the saved names, and they start again at version 1`() =
        testApplication {
            val s = serve()
            saveRoot()
            putNames(baseVersion = 0)
            putNames(baseVersion = 1)

            assertEquals(HttpStatusCode.OK, client.delete("/v1/key-backup").status)

            assertNull(s.h.store.savedNames[s.me.userId])
            assertEquals(Names(exists = false, version = 0), names())
            assertEquals(1, s.logged().count { it == SecurityEventType.KEY_BACKUP_OFF })
            assertEquals("identity.key_backup_off", code(putNames(baseVersion = 0).bodyAsText()))
            saveRoot()
            assertEquals(1, savedVersion(putNames(baseVersion = 0).bodyAsText()))
        }
}
