package com.circaso.ramp.data.support

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.models.AssertionOptions
import com.circaso.ramp.domain.models.CreationOptions
import com.circaso.ramp.domain.models.CredentialRef
import com.circaso.ramp.domain.models.Passkey
import com.circaso.ramp.domain.ports.NewCredential
import com.circaso.ramp.domain.ports.PasskeyCeremonies
import com.circaso.ramp.domain.ports.PendingCeremony
import com.circaso.ramp.domain.ports.VerifiedAssertion
import com.yubico.webauthn.AssertionRequest
import com.yubico.webauthn.CredentialRepository
import com.yubico.webauthn.FinishAssertionOptions
import com.yubico.webauthn.FinishRegistrationOptions
import com.yubico.webauthn.RegisteredCredential
import com.yubico.webauthn.RelyingParty
import com.yubico.webauthn.StartRegistrationOptions
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria
import com.yubico.webauthn.data.AuthenticatorTransport
import com.yubico.webauthn.data.ByteArray
import com.yubico.webauthn.data.PublicKeyCredential
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor
import com.yubico.webauthn.data.PublicKeyCredentialRequestOptions
import com.yubico.webauthn.data.RelyingPartyIdentity
import com.yubico.webauthn.data.ResidentKeyRequirement
import com.yubico.webauthn.data.UserIdentity
import com.yubico.webauthn.data.UserVerificationRequirement
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.Optional
import java.util.UUID

/**
 * The merchant portal as a WebAuthn relying party, on Yubico's `java-webauthn-server` (2026-11-24).
 *
 * **The relying party id is `merchants.circaso.com`**, not `circaso.com`: a passkey is then usable only on the portal's
 * own host, never by any other circaso.com subdomain (a marketing page, a future app site), and stays apart from the
 * app's `circaso.com` passkeys that seal the chat backup. The browser's origin is always `https://merchants.circaso.com`
 * whichever API (sandbox or production) the portal's worker forwards to, so one id serves both; each environment keeps
 * its own credentials. On a developer's machine ([local]) the id is `localhost` and the dev servers' origins.
 *
 * User verification is required (a fingerprint, face or device PIN on top of possession), and new passkeys are
 * discoverable, so the portal can sign in without an email. The user handle is the merchant id's 16 bytes: never the
 * email. Nothing here logs the credential, the challenge or the email.
 */
class YubicoPasskeys(private val rpId: String = RP_ID, private val origins: Set<String> = setOf(ORIGIN)) :
    PasskeyCeremonies {
    private val log = LoggerFactory.getLogger(YubicoPasskeys::class.java)
    private val identity =
        RelyingPartyIdentity
            .builder()
            .id(rpId)
            .name(RP_NAME)
            .build()

    override fun registration(
        merchantId: MerchantId,
        userName: String,
        displayName: String,
        exclude: List<Passkey>,
    ): PendingCeremony {
        val handle = handleOf(merchantId)
        val request =
            party(Known(handle, merchantId.toString(), exclude)).startRegistration(
                StartRegistrationOptions
                    .builder()
                    .user(
                        UserIdentity
                            .builder()
                            .name(merchantId.toString())
                            .displayName(displayName.ifBlank { userName })
                            .id(handle)
                            .build(),
                    ).authenticatorSelection(
                        AuthenticatorSelectionCriteria
                            .builder()
                            .residentKey(ResidentKeyRequirement.REQUIRED)
                            .userVerification(UserVerificationRequirement.REQUIRED)
                            .build(),
                    ).timeout(TIMEOUT_MS)
                    .build(),
            )
        val options =
            CreationOptions(
                challenge = request.challenge.base64Url,
                rpId = rpId,
                rpName = RP_NAME,
                userId = handle.base64Url,
                // What the browser shows in its passkey list: the merchant's email, their account name.
                userName = userName,
                userDisplayName = displayName.ifBlank { userName },
                algorithms = request.pubKeyCredParams.map { it.alg.id },
                excludeCredentials = exclude.map { it.toRef() },
                residentKey = "required",
                userVerification = "required",
                attestation = "none",
                timeoutMs = TIMEOUT_MS,
            )
        return PendingCeremony(options, request.toJson())
    }

    override fun register(state: String, credentialJson: String): NewCredential? =
        try {
            val request = PublicKeyCredentialCreationOptions.fromJson(state)
            val response = PublicKeyCredential.parseRegistrationResponseJson(credentialJson)
            val handle = request.user.id
            val result =
                party(Known(handle, request.user.name, emptyList())).finishRegistration(
                    FinishRegistrationOptions
                        .builder()
                        .request(request)
                        .response(response)
                        .build(),
                )
            NewCredential(
                credentialId = result.keyId.id.bytes,
                publicKeyCose = result.publicKeyCose.bytes,
                signCount = result.signatureCount,
                transports =
                    result.keyId.transports
                        .map { set -> set.map { it.id } }
                        .orElse(emptyList()),
                backupEligible = result.isBackupEligible,
                backedUp = result.isBackedUp,
            )
        } catch (e: Exception) {
            // The library says why; that is safe to log (no key material, no challenge).
            log.info("passkey registration refused: {}", e.message?.take(MAX_REASON))
            null
        }

    override fun assertion(challenge: kotlin.ByteArray, allow: List<Passkey>): AssertionOptions =
        AssertionOptions(
            challenge = ByteArray(challenge).base64Url,
            rpId = rpId,
            allowCredentials = allow.map { it.toRef() },
            userVerification = "required",
            timeoutMs = TIMEOUT_MS,
        )

    override fun credentialIdOf(credentialJson: String): kotlin.ByteArray? =
        try {
            PublicKeyCredential.parseAssertionResponseJson(credentialJson).id.bytes
        } catch (_: Exception) {
            null
        }

    override fun verify(
        challenge: kotlin.ByteArray,
        credentialJson: String,
        passkey: Passkey,
    ): VerifiedAssertion? =
        try {
            val response = PublicKeyCredential.parseAssertionResponseJson(credentialJson)
            val handle = handleOf(passkey.merchantId)
            val request =
                AssertionRequest
                    .builder()
                    .publicKeyCredentialRequestOptions(
                        PublicKeyCredentialRequestOptions
                            .builder()
                            .challenge(ByteArray(challenge))
                            .rpId(rpId)
                            .allowCredentials(listOf(passkey.toDescriptor()))
                            .userVerification(UserVerificationRequirement.REQUIRED)
                            .timeout(TIMEOUT_MS)
                            .build(),
                    ).userHandle(handle)
                    .build()
            val result =
                party(Known(handle, passkey.merchantId.toString(), listOf(passkey))).finishAssertion(
                    FinishAssertionOptions
                        .builder()
                        .request(request)
                        .response(response)
                        .build(),
                )
            if (result.isSuccess) VerifiedAssertion(result.signatureCount, result.isBackedUp) else null
        } catch (e: Exception) {
            log.info("passkey assertion refused: {}", e.message?.take(MAX_REASON))
            null
        }

    private fun party(known: Known): RelyingParty =
        RelyingParty
            .builder()
            .identity(identity)
            .credentialRepository(known)
            .origins(origins)
            .build()

    /** The one merchant and their passkeys a ceremony is about: the library asks about nobody else. */
    private class Known(val handle: ByteArray, val name: String, val passkeys: List<Passkey>) : CredentialRepository {
        override fun getCredentialIdsForUsername(username: String): Set<PublicKeyCredentialDescriptor> =
            if (username == name) passkeys.map { it.toDescriptor() }.toSet() else emptySet()

        override fun getUserHandleForUsername(username: String): Optional<ByteArray> =
            if (username == name) Optional.of(handle) else Optional.empty()

        override fun getUsernameForUserHandle(userHandle: ByteArray): Optional<String> =
            if (userHandle == handle) Optional.of(name) else Optional.empty()

        override fun lookup(credentialId: ByteArray, userHandle: ByteArray): Optional<RegisteredCredential> =
            Optional.ofNullable(
                passkeys
                    .firstOrNull { ByteArray(it.credentialId) == credentialId && userHandle == handle }
                    ?.toRegistered(handle),
            )

        override fun lookupAll(credentialId: ByteArray): Set<RegisteredCredential> =
            passkeys.filter { ByteArray(it.credentialId) == credentialId }.map { it.toRegistered(handle) }.toSet()
    }

    companion object {
        const val RP_ID = "merchants.circaso.com"
        const val ORIGIN = "https://merchants.circaso.com"
        const val RP_NAME = "Circaso Merchants"
        const val TIMEOUT_MS = 120_000L
        private const val MAX_REASON = 200

        /** A developer's machine: the portal's dev servers on localhost. */
        fun local(): YubicoPasskeys =
            YubicoPasskeys(
                rpId = "localhost",
                origins = setOf("http://localhost:5173", "http://localhost:4174", "http://localhost:4173"),
            )

        /** The user handle: the merchant id's 16 bytes. */
        fun handleOf(merchantId: MerchantId): ByteArray {
            val id: UUID = merchantId.value
            return ByteArray(
                ByteBuffer
                    .allocate(16)
                    .putLong(id.mostSignificantBits)
                    .putLong(id.leastSignificantBits)
                    .array(),
            )
        }

        private fun Passkey.toDescriptor(): PublicKeyCredentialDescriptor =
            PublicKeyCredentialDescriptor
                .builder()
                .id(ByteArray(credentialId))
                .transports(transports.map { AuthenticatorTransport.of(it) }.toSet())
                .build()

        private fun Passkey.toRef(): CredentialRef = CredentialRef(ByteArray(credentialId).base64Url, transports)

        private fun Passkey.toRegistered(handle: ByteArray): RegisteredCredential =
            RegisteredCredential
                .builder()
                .credentialId(ByteArray(credentialId))
                .userHandle(handle)
                .publicKeyCose(ByteArray(publicKeyCose))
                .signatureCount(signCount)
                .build()
    }
}
