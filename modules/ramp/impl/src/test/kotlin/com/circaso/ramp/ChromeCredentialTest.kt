package com.circaso.ramp

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.data.support.YubicoPasskeys
import com.circaso.ramp.domain.models.Passkey
import com.circaso.ramp.presentation.dto.PasskeyCredentialDto
import com.circaso.ramp.presentation.mappers.toJson
import com.circaso.shared.CircasoJson
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions
import com.yubico.webauthn.data.PublicKeyCredentialParameters
import com.yubico.webauthn.data.RelyingPartyIdentity
import com.yubico.webauthn.data.ResidentKeyRequirement
import com.yubico.webauthn.data.UserIdentity
import com.yubico.webauthn.data.UserVerificationRequirement
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.nio.ByteBuffer
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.yubico.webauthn.data.ByteArray as YubicoBytes

/**
 * A real browser's passkey against the server's checks (2026-11-24): `resources/webauthn/chrome-virtual-authenticator.json`
 * is what Chrome's virtual authenticator (CDP `WebAuthn.addVirtualAuthenticator`: ctap2, internal, resident key, user
 * verified) answered on `http://localhost:5173`, the backend's local relying party, for fixed challenges. It goes
 * through the same path the portal's credential does (the request DTO, then the library), so a change in what browsers
 * send, or in how we re-encode it, shows here. Regenerate with the portal repo's virtual-authenticator script.
 */
class ChromeCredentialTest {
    @Serializable
    private data class Fixture(
        val regChallenge: String,
        val getChallenge: String,
        val userId: String,
        val registration: JsonObject,
        val assertion: JsonObject,
    )

    private val fixture: Fixture =
        CircasoJson.decodeFromString(
            Fixture.serializer(),
            javaClass.getResource("/webauthn/chrome-virtual-authenticator.json")!!.readText(),
        )
    private val ceremonies = YubicoPasskeys.local()

    private fun bytes(b64: String) = Base64.getUrlDecoder().decode(b64)

    /** The browser's JSON as the portal's request carries it: through the DTO, unknown fields dropped. */
    private fun throughDto(json: JsonObject): String =
        CircasoJson.decodeFromJsonElement(PasskeyCredentialDto.serializer(), json).toJson()

    private fun state(): String =
        PublicKeyCredentialCreationOptions
            .builder()
            .rp(
                RelyingPartyIdentity
                    .builder()
                    .id("localhost")
                    .name("Circaso Merchants")
                    .build(),
            ).user(
                UserIdentity
                    .builder()
                    .name("fixture@example.com")
                    .displayName("Fixture")
                    .id(YubicoBytes(bytes(fixture.userId)))
                    .build(),
            ).challenge(YubicoBytes(bytes(fixture.regChallenge)))
            .pubKeyCredParams(listOf(PublicKeyCredentialParameters.ES256, PublicKeyCredentialParameters.RS256))
            .authenticatorSelection(
                AuthenticatorSelectionCriteria
                    .builder()
                    .residentKey(ResidentKeyRequirement.REQUIRED)
                    .userVerification(UserVerificationRequirement.REQUIRED)
                    .build(),
            ).build()
            .toJson()

    @Test
    fun `chrome's registration is accepted and its assertion verifies, but only over its own challenge`() {
        val made = assertNotNull(ceremonies.register(state(), throughDto(fixture.registration)))
        assertTrue(made.transports.isNotEmpty())
        val handle = ByteBuffer.wrap(bytes(fixture.userId))
        val merchant = MerchantId(UUID(handle.long, handle.long))
        val passkey =
            Passkey(
                UUID.randomUUID(),
                merchant,
                made.credentialId,
                made.publicKeyCose,
                made.signCount,
                "Chrome",
                made.transports,
                made.backupEligible,
                made.backedUp,
                Instant.now(),
                null,
            )
        val assertion = throughDto(fixture.assertion)

        assertNotNull(ceremonies.verify(bytes(fixture.getChallenge), assertion, passkey), "the real assertion verifies")
        assertNull(ceremonies.verify(bytes(fixture.regChallenge), assertion, passkey), "not over another challenge")
        assertNull(
            YubicoPasskeys().verify(bytes(fixture.getChallenge), assertion, passkey),
            "localhost's passkey is no good for merchants.circaso.com",
        )
    }
}
