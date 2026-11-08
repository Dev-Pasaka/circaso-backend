package com.circaso.identity

import com.circaso.identity.api.AccountDirectory
import com.circaso.identity.api.AccountKind
import com.circaso.identity.api.AccountStatus
import com.circaso.identity.api.Principal
import com.circaso.identity.api.TokenVerifier
import com.circaso.identity.data.security.EcdsaDeviceKeyVerifier
import com.circaso.identity.data.security.Ed25519Tokens
import com.circaso.identity.domain.errors.IdentityError
import com.circaso.identity.domain.ports.DeviceKeyVerifier
import com.circaso.identity.presentation.warmup.IdentityWarmUp
import com.circaso.infrastructure.api.Secrets
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

/** Q7 (audit 2026-11-05): the warm-up runs a refresh's crypto (tokens, refresh hash, ES256 device proof), writing nothing. */
class IdentityWarmUpTest {
    private val secrets =
        object : Secrets {
            override fun get(name: String): String? = null
        }
    private val tokens = Ed25519Tokens(secrets, local = true, clock = Clock.systemUTC())
    private val store = MemoryStore()
    private val proofs = CopyOnWriteArrayList<Boolean>()
    private val keys =
        object : DeviceKeyVerifier by EcdsaDeviceKeyVerifier() {
            private val real = EcdsaDeviceKeyVerifier()

            override fun verify(
                publicKey: String,
                algorithm: String,
                payload: ByteArray,
                signature: ByteArray,
            ): Boolean = real.verify(publicKey, algorithm, payload, signature).also { proofs += it }
        }
    private val verifier =
        object : TokenVerifier {
            override suspend fun verify(accessToken: String): Resource<Principal> =
                Resource.error(IdentityError.SessionRevoked)
        }
    private val accounts =
        object : AccountDirectory {
            override suspend fun status(userId: UserId): AccountStatus? = null

            override suspend fun kind(userId: UserId): AccountKind? = null

            override suspend fun resolvePhones(phones: Set<PhoneNumber>): Map<PhoneNumber, UserId> = emptyMap()
        }

    @Test
    fun `a pass checks a good device proof and a broken one, and writes nothing`() =
        runBlocking<Unit> {
            val warmUp = IdentityWarmUp(tokens, verifier, accounts, store, store, keys)

            repeat(3) { warmUp.run() }

            assertEquals(
                List(3) { listOf(true, false) }.flatten(),
                proofs,
                "the phone key's proof verifies; a changed one doesn't",
            )
            assertEquals(0, store.sessions.size + store.devices.size + store.log.size, "nothing written")
        }
}
