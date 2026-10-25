package com.circaso.messaging

import com.circaso.messaging.domain.models.FetchedBundle
import com.circaso.messaging.domain.support.KeyProofs
import com.circaso.messaging.domain.support.MessagingConfig
import com.circaso.messaging.domain.support.SharedGroups
import com.circaso.messaging.domain.usecases.AddOneTimeKeys
import com.circaso.messaging.domain.usecases.FetchKeyBundle
import com.circaso.messaging.domain.usecases.GetKeyStatus
import com.circaso.messaging.domain.usecases.PublishKeys
import com.circaso.messaging.presentation.mappers.SignedUpload
import com.circaso.messaging.presentation.mappers.addOneTimeKeysCommand
import com.circaso.messaging.presentation.mappers.publishKeysCommand
import com.circaso.shared.DeviceId
import com.circaso.shared.Resource
import com.circaso.shared.Scope
import com.circaso.shared.UserId
import com.circaso.shared.flatMap
import com.circaso.web.CallPrincipal
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M1 to M3 against in-memory fakes, with real ES256 device signatures over the bodies a phone would send. */
class KeyDirectoryTest {
    private val now = Instant.parse("2026-10-25T09:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val config = MessagingConfig()
    private val keys = MemoryKeys()
    private val signatures = FakeDeviceSignatures()
    private val reach = FakeReach()
    private val rateLimit = MemoryRateLimit()
    private val proofs = KeyProofs(signatures, clock, config.proofWindow)
    private val publishKeys = PublishKeys(keys, proofs, clock)
    private val addOneTimeKeys = AddOneTimeKeys(keys, proofs, config)
    private val getKeyStatus = GetKeyStatus(keys)
    private val fetchKeyBundle = FetchKeyBundle(keys, reach, SharedGroups(MemoryChats()), rateLimit, config)

    private fun <T> Resource<T>.data(): T = (this as Resource.Success).data

    private fun Resource<*>.errorCode(): String = (this as Resource.Error).error.code

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    private fun key(seed: Int) = b64(ByteArray(33) { (seed + it).toByte() })

    /** A phone with its hardware-backed device key, registered with identity. */
    private inner class Phone(
        val userId: UserId = UserId.new(),
        val deviceId: DeviceId = DeviceId.new(),
        val pair: KeyPair = newPair(),
        register: Boolean = true,
    ) {
        init {
            if (register) signatures.register(userId, deviceId, pair.public)
        }

        val principal get() = CallPrincipal(userId, deviceId, UUID.randomUUID(), Scope.APP)

        /** Signs `timestamp + "\n" + hex(SHA-256(body))` and sends the proof in the two headers, as the app does. */
        fun sign(
            body: String,
            at: Instant = now,
            with: KeyPair = pair,
        ): SignedUpload {
            val digest = MessageDigest.getInstance("SHA-256").digest(body.toByteArray())
            val payload = "$at\n" + digest.joinToString("") { "%02x".format(it) }
            val signature =
                Signature.getInstance("SHA256withECDSA").run {
                    initSign(with.private)
                    update(payload.toByteArray())
                    sign()
                }
            return SignedUpload(body.toByteArray(), at.toString(), b64(signature))
        }
    }

    private fun SignedUpload.withBody(changed: String) = SignedUpload(changed.toByteArray(), timestamp, signature)

    private fun SignedUpload.withTimestamp(changed: String?) = SignedUpload(body, changed, signature)

    private fun SignedUpload.withSignature(changed: String?) = SignedUpload(body, timestamp, changed)

    private fun newPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

    private fun oneTimeJson(ids: IntRange) = ids.joinToString(",") { """{"keyId":$it,"publicKey":"${key(it)}"}""" }

    private fun publishBody(signedPreKeyId: Int = 7, oneTimeIds: IntRange = 101..110) =
        """{"identityKey":"${key(1)}","signedPreKey":{"keyId":$signedPreKeyId,"publicKey":"${key(2)}",""" +
            """"signature":"${b64(ByteArray(64) { 3 })}"},"oneTimePreKeys":[${oneTimeJson(oneTimeIds)}]}"""

    private fun topUpBody(ids: IntRange) = """{"oneTimePreKeys":[${oneTimeJson(ids)}]}"""

    private suspend fun publish(phone: Phone, upload: SignedUpload) =
        publishKeysCommand(phone.principal, upload).flatMap { publishKeys(it) }

    private suspend fun topUp(phone: Phone, upload: SignedUpload) =
        addOneTimeKeysCommand(phone.principal, upload).flatMap { addOneTimeKeys(it) }

    // ---- M1 publish ----

    @Test
    fun `a signed upload publishes the bundle and its one-time prekeys`() =
        runTest {
            val amina = Phone()

            assertEquals(10, publish(amina, amina.sign(publishBody())).data())

            val status = getKeyStatus(amina.userId, amina.deviceId).data()
            assertEquals(10, status.oneTimePreKeysLeft)
            assertEquals(now, status.signedPreKeyAt)
            assertEquals(amina.deviceId, keys.bundles[amina.userId]!!.deviceId)
        }

    @Test
    fun `the body is hashed exactly as sent, whitespace and field order included`() =
        runTest {
            val amina = Phone()
            val spaced = publishBody().replace(",", ", ").replace("{", "{ ")

            assertEquals(10, publish(amina, amina.sign(spaced)).data())
        }

    @Test
    fun `a signature by another key is refused`() =
        runTest {
            val amina = Phone()

            val result = publish(amina, amina.sign(publishBody(), with = newPair()))

            assertEquals("messaging.keys_proof_invalid", result.errorCode())
            assertTrue(keys.bundles.isEmpty())
        }

    @Test
    fun `the string to sign is the one in the mobile guide's example, byte for byte`() {
        val body =
            """{"oneTimePreKeys":[{"keyId":101,"publicKey":"BQECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8g"}]}"""
        val upload = SignedUpload(body.toByteArray(), "2026-10-25T09:00:00Z", b64(ByteArray(70) { 1 }))

        val command = addOneTimeKeysCommand(Phone().principal, upload).data()

        assertEquals(93, body.toByteArray().size)
        assertEquals(
            "2026-10-25T09:00:00Z\n94a80dfab795f31ad61df91575b9a16e6c60ba7e0b056b80b7ed87536b08660a",
            command.proof.signedPayload.decodeToString(),
        )
    }

    @Test
    fun `a body changed after it was signed is refused`() =
        runTest {
            val amina = Phone()
            val signed = amina.sign(publishBody())

            val tampered = signed.withBody(publishBody().replace(key(1), key(9)))
            val reformatted = signed.withBody(publishBody() + "\n")

            assertEquals("messaging.keys_proof_invalid", publish(amina, tampered).errorCode())
            assertEquals("messaging.keys_proof_invalid", publish(amina, reformatted).errorCode())
        }

    @Test
    fun `the timestamp is covered by the signature`() =
        runTest {
            val amina = Phone()
            val signed = amina.sign(publishBody(), at = now.minus(Duration.ofMinutes(1)))

            val moved = signed.withTimestamp(now.toString())

            assertEquals("messaging.keys_proof_invalid", publish(amina, moved).errorCode())
            assertEquals(10, publish(amina, signed).data())
        }

    @Test
    fun `missing or unreadable proof headers are a failed proof`() =
        runTest {
            val amina = Phone()
            val signed = amina.sign(publishBody())

            for (upload in listOf(
                signed.withTimestamp(null),
                signed.withSignature(null),
                signed.withTimestamp("yesterday"),
                signed.withSignature("not base64!"),
            )) {
                assertEquals("messaging.keys_proof_invalid", publish(amina, upload).errorCode())
            }
            assertEquals("messaging.keys_proof_invalid", topUp(amina, signed.withSignature(null)).errorCode())
        }

    @Test
    fun `a proof from a phone that is not the caller's registered device is refused`() =
        runTest {
            val amina = Phone()
            // the token names a device identity doesn't know, though the body is signed with Amina's real key
            val otherDevice = Phone(userId = amina.userId, pair = amina.pair, register = false)

            assertEquals(
                "messaging.keys_proof_invalid",
                publish(otherDevice, otherDevice.sign(publishBody())).errorCode(),
            )
        }

    @Test
    fun `a proof older or newer than five minutes is refused`() =
        runTest {
            val amina = Phone()

            val stale = publish(amina, amina.sign(publishBody(), at = now.minus(Duration.ofMinutes(6))))
            val future = publish(amina, amina.sign(publishBody(), at = now.plus(Duration.ofMinutes(6))))
            val recent = publish(amina, amina.sign(publishBody(), at = now.minus(Duration.ofMinutes(4))))

            assertEquals("messaging.keys_proof_invalid", stale.errorCode())
            assertEquals("messaging.keys_proof_invalid", future.errorCode())
            assertEquals(10, recent.data())
        }

    @Test
    fun `a second upload replaces the signed prekey and drops the one-time prekeys of the first`() =
        runTest {
            val amina = Phone()
            publish(amina, amina.sign(publishBody(signedPreKeyId = 7, oneTimeIds = 101..110))).data()

            val left = publish(amina, amina.sign(publishBody(signedPreKeyId = 8, oneTimeIds = 105..124))).data()

            assertEquals(20, left)
            assertEquals(8, keys.bundles[amina.userId]!!.signedPreKey.keyId)
            assertEquals((105..124).toSet(), keys.oneTimeKeyIds(amina.userId))
        }

    @Test
    fun `a signed upload is shape-checked`() =
        runTest {
            val amina = Phone()

            assertEquals("request.malformed", publish(amina, amina.sign("not json")).errorCode())
            val duplicate = publishBody().replace(""""keyId":102""", """"keyId":101""")
            assertEquals("request.malformed", publish(amina, amina.sign(duplicate)).errorCode())
            val notBase64 = publishBody().replace(key(1), "not base64!")
            assertEquals("request.malformed", publish(amina, amina.sign(notBase64)).errorCode())
        }

    @Test
    fun `more than 100 one-time prekeys in one upload is too many`() =
        runTest {
            val amina = Phone()

            val result = publish(amina, amina.sign(publishBody(oneTimeIds = 1..101)))

            assertEquals("messaging.too_many_prekeys", result.errorCode())
            assertEquals(100, publish(amina, amina.sign(publishBody(oneTimeIds = 1..100))).data())
            assertEquals("messaging.too_many_prekeys", topUp(amina, amina.sign(topUpBody(201..301))).errorCode())
        }

    // ---- M1 top-up ----

    @Test
    fun `a top-up adds keys up to 100 and refuses one that would pass it`() =
        runTest {
            val amina = Phone()
            publish(amina, amina.sign(publishBody(oneTimeIds = 1..60))).data()

            assertEquals(100, topUp(amina, amina.sign(topUpBody(61..100))).data())
            assertEquals("messaging.too_many_prekeys", topUp(amina, amina.sign(topUpBody(101..101))).errorCode())
            assertEquals(100, keys.oneTimeKeyIds(amina.userId).size)
        }

    @Test
    fun `a top-up that resends a stored key id is refused whole`() =
        runTest {
            val amina = Phone()
            publish(amina, amina.sign(publishBody(oneTimeIds = 1..10))).data()

            val result = topUp(amina, amina.sign(topUpBody(10..20)))

            assertEquals("messaging.prekey_id_exists", result.errorCode())
            assertEquals((1..10).toSet(), keys.oneTimeKeyIds(amina.userId), "nothing from the refused upload is kept")
            assertEquals(20, topUp(amina, amina.sign(topUpBody(11..20))).data())
        }

    @Test
    fun `a top-up is device-signed too`() =
        runTest {
            val amina = Phone()
            publish(amina, amina.sign(publishBody())).data()

            val result = topUp(amina, amina.sign(topUpBody(200..210), with = newPair()))

            assertEquals("messaging.keys_proof_invalid", result.errorCode())
        }

    @Test
    fun `a top-up or status from a phone without its own bundle answers keys_not_found`() =
        runTest {
            val amina = Phone()
            assertEquals("messaging.keys_not_found", topUp(amina, amina.sign(topUpBody(1..5))).errorCode())
            assertEquals("messaging.keys_not_found", getKeyStatus(amina.userId, amina.deviceId).errorCode())

            publish(amina, amina.sign(publishBody())).data()
            val newPhone = Phone(userId = amina.userId)

            assertEquals("messaging.keys_not_found", topUp(newPhone, newPhone.sign(topUpBody(1..5))).errorCode())
            assertEquals("messaging.keys_not_found", getKeyStatus(amina.userId, newPhone.deviceId).errorCode())
        }

    // ---- M3 fetch ----

    private suspend fun aminaWithKeys(oneTimeIds: IntRange = 101..103): Phone {
        val amina = Phone()
        publish(amina, amina.sign(publishBody(oneTimeIds = oneTimeIds))).data()
        return amina
    }

    @Test
    fun `each fetch uses up one prekey, then the bundle comes without one`() =
        runTest {
            val amina = aminaWithKeys(101..103)
            val brian = UserId.new()
            reach.pairs += brian to amina.userId

            val fetched = (1..4).map { fetchKeyBundle(brian, amina.userId).data() }

            assertEquals(listOf(101, 102, 103, null), fetched.map { it.oneTimePreKey?.keyId })
            assertTrue(fetched.all { it.identityKey == key(1) && it.signedPreKey.keyId == 7 })
            assertEquals(0, getKeyStatus(amina.userId, amina.deviceId).data().oneTimePreKeysLeft)
        }

    @Test
    fun `concurrent fetches never get the same prekey`() =
        runTest {
            val amina = aminaWithKeys(1..50)
            val callers = (1..60).map { UserId.new() }
            callers.forEach { reach.pairs += it to amina.userId }

            val bundles: List<FetchedBundle> =
                callers.map { async { fetchKeyBundle(it, amina.userId).data() } }.awaitAll()

            val claimed = bundles.mapNotNull { it.oneTimePreKey?.keyId }
            assertEquals(50, claimed.size)
            assertEquals(claimed.size, claimed.toSet().size)
        }

    @Test
    fun `someone who can't reach the person is refused before the bundle is looked up`() =
        runTest {
            val amina = aminaWithKeys()
            val stranger = UserId.new()

            assertEquals("users.not_reachable", fetchKeyBundle(stranger, amina.userId).errorCode())
            assertEquals("users.not_reachable", fetchKeyBundle(stranger, UserId.new()).errorCode())
            assertEquals(3, keys.oneTimeKeyIds(amina.userId).size, "no prekey was used up")
        }

    @Test
    fun `a reachable person without keys answers keys_not_found`() =
        runTest {
            val brian = UserId.new()
            val noKeys = UserId.new()
            reach.pairs += brian to noKeys

            assertEquals("messaging.keys_not_found", fetchKeyBundle(brian, noKeys).errorCode())
        }

    @Test
    fun `the 61st fetch in an hour is rate limited`() =
        runTest {
            val amina = aminaWithKeys()
            val brian = UserId.new()
            reach.pairs += brian to amina.userId

            repeat(60) { assertNotNull(fetchKeyBundle(brian, amina.userId).data()) }
            val refused = fetchKeyBundle(brian, amina.userId)

            assertEquals("messaging.rate_limited", refused.errorCode())
            assertEquals(42 * 60L, (refused as Resource.Error).error.details!!.retryAfterSeconds)
            assertNull(keys.bundles[brian])
        }
}
