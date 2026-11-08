package com.circaso.identity.presentation.warmup

import com.circaso.identity.api.AccountDirectory
import com.circaso.identity.api.SessionId
import com.circaso.identity.api.TokenVerifier
import com.circaso.identity.domain.models.DeviceProof
import com.circaso.identity.domain.ports.DeviceKeyVerifier
import com.circaso.identity.domain.ports.TokenIssuer
import com.circaso.identity.domain.repositories.DeviceRepository
import com.circaso.identity.domain.repositories.SessionRepository
import com.circaso.identity.domain.support.DeviceProofs
import com.circaso.identity.domain.support.SessionOpener
import com.circaso.identity.domain.support.randomToken
import com.circaso.identity.domain.support.sha256Hex
import com.circaso.infrastructure.api.WarmUp
import com.circaso.shared.DeviceId
import com.circaso.shared.Scope
import com.circaso.shared.UserId
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64

/**
 * What the gateway asks on every request, read-only: an access token's signature and claims, then the session's
 * deny-list entry in Redis (`EXISTS`), for a token signed here for random ids that no session holds (and one with a
 * broken signature, the refusal path). Then the crypto of a refresh (I7) without its transaction: a refresh token made
 * and read back, its hash, an app and an agent token signed, and a device proof (ES256, the phone's key) signed with a
 * key made here and checked as the refresh checks it, once good and once broken. Then the account, session and device
 * reads behind sign-in and refresh, for the same random ids. No token is handed out, and no id it names exists.
 */
class IdentityWarmUp(
    private val issuer: TokenIssuer,
    private val verifier: TokenVerifier,
    private val accounts: AccountDirectory,
    private val sessions: SessionRepository,
    private val devices: DeviceRepository,
    private val deviceKeys: DeviceKeyVerifier,
) : WarmUp {
    override val name = "identity"

    /** A stand-in phone key, made once: generating an EC key is slower than anything a request does. */
    private val phone: KeyPair by lazy {
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    }
    private val phonePublicKey: String by lazy { Base64.getEncoder().encodeToString(phone.public.encoded) }

    override suspend fun run() {
        val userId = UserId.new()
        val deviceId = DeviceId.new()
        val sessionId = SessionId.new()
        val token = issuer.issue(userId, deviceId, sessionId, Scope.APP, sessionVersion = 1).token
        verifier.verify(token)
        verifier.verify(token.dropLast(2) + if (token.endsWith("AA")) "BB" else "AA")

        refreshCrypto(userId, deviceId, sessionId)

        accounts.status(userId)
        sessions.find(sessionId)
        devices.find(userId, deviceId)
    }

    private fun refreshCrypto(
        userId: UserId,
        deviceId: DeviceId,
        sessionId: SessionId,
    ) {
        val refresh = SessionOpener.refreshString(sessionId, randomToken())
        SessionOpener.sessionIdOf(refresh)
        sha256Hex(refresh)
        issuer.issue(userId, deviceId, sessionId, Scope.APP, sessionVersion = 1)
        issuer.issue(userId, deviceId, sessionId, Scope.AGENT, sessionVersion = 1)

        val unsigned = DeviceProof(Instant.now(), ByteArray(0))
        val payload = DeviceProofs.refreshPayload(refresh, unsigned).toByteArray()
        val signature =
            Signature.getInstance("SHA256withECDSA").run {
                initSign(phone.private)
                update(payload)
                sign()
            }
        deviceKeys.isValid(phonePublicKey, ES256)
        deviceKeys.verify(phonePublicKey, ES256, payload, signature)
        deviceKeys.verify(phonePublicKey, ES256, payload + 0, signature)
    }

    private companion object {
        const val ES256 = "ES256"
    }
}
