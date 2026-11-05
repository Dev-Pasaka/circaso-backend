package com.circaso.identity

import com.circaso.identity.api.Principal
import com.circaso.identity.data.security.EcdsaDeviceKeyVerifier
import com.circaso.identity.data.security.Ed25519Tokens
import com.circaso.identity.data.security.KeyedPhoneProtection
import com.circaso.identity.domain.models.DeviceInfo
import com.circaso.identity.domain.models.DeviceProof
import com.circaso.identity.domain.models.NextStep
import com.circaso.identity.domain.models.RegisterDeviceCommand
import com.circaso.identity.domain.models.RegisteredDevice
import com.circaso.identity.domain.models.SignInCommand
import com.circaso.identity.domain.models.SignInResult
import com.circaso.identity.domain.models.StartPhoneCommand
import com.circaso.identity.domain.models.VerifyCodeCommand
import com.circaso.identity.domain.ports.AttestationMode
import com.circaso.identity.domain.ports.AttestationVerifier
import com.circaso.identity.domain.ports.Transactor
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.repositories.DeviceRepository
import com.circaso.identity.domain.repositories.IdentityStore
import com.circaso.identity.domain.repositories.ProviderRepository
import com.circaso.identity.domain.repositories.SessionRepository
import com.circaso.identity.domain.support.DeviceProofs
import com.circaso.identity.domain.support.IdentityConfig
import com.circaso.identity.domain.support.PhoneOwnership
import com.circaso.identity.domain.support.SessionOpener
import com.circaso.identity.domain.usecases.GetSecurityOverview
import com.circaso.identity.domain.usecases.IssueNonce
import com.circaso.identity.domain.usecases.LinkProvider
import com.circaso.identity.domain.usecases.RefreshSession
import com.circaso.identity.domain.usecases.RegisterDevice
import com.circaso.identity.domain.usecases.RequestAccountDeletion
import com.circaso.identity.domain.usecases.RequestPhoneRelease
import com.circaso.identity.domain.usecases.RequestSupportRecovery
import com.circaso.identity.domain.usecases.SignInWithProvider
import com.circaso.identity.domain.usecases.SignOut
import com.circaso.identity.domain.usecases.StartPhoneVerification
import com.circaso.identity.domain.usecases.UnlinkProvider
import com.circaso.identity.domain.usecases.VerifyAccessToken
import com.circaso.identity.domain.usecases.VerifyAccessTokenAlongside
import com.circaso.identity.domain.usecases.VerifyCarrierToken
import com.circaso.identity.domain.usecases.VerifyPhoneCode
import com.circaso.infrastructure.api.Secrets
import com.circaso.integrations.api.CarrierPhoneVerification
import com.circaso.integrations.api.CodeCheck
import com.circaso.integrations.api.Email
import com.circaso.integrations.api.EmailGateway
import com.circaso.integrations.api.EmailReceipt
import com.circaso.integrations.api.EmailTemplate
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.NoVendorVerification
import com.circaso.integrations.api.PhoneVerificationGateway
import com.circaso.integrations.api.SmsGateway
import com.circaso.integrations.api.SmsPurpose
import com.circaso.integrations.api.SmsReceipt
import com.circaso.integrations.api.SocialIdentity
import com.circaso.integrations.api.SocialProvider
import com.circaso.integrations.api.SocialSignIn
import com.circaso.shared.DeviceId
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.getOrNull
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Clock
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

class FakeSocial : SocialSignIn {
    override suspend fun verify(
        provider: SocialProvider,
        idToken: String,
        nonce: String,
    ): Resource<SocialIdentity> {
        val parts = idToken.split(':')
        if (parts.size < 2 || parts[0] != "dev") return Resource.error(IntegrationError.Rejected("bad token"))
        return Resource.success(
            SocialIdentity(
                provider,
                parts[1],
                parts.getOrNull(2)?.takeIf {
                    it.isNotBlank()
                },
                true,
                parts.getOrNull(3),
                null,
            ),
        )
    }

    override suspend fun revokeApple(appleToken: String): Resource<Unit> = Resource.success(Unit)
}

class RecordingSms : SmsGateway {
    val texts = CopyOnWriteArrayList<String>()
    var down = false

    override suspend fun send(
        to: PhoneNumber,
        text: String,
        purpose: SmsPurpose,
    ): Resource<SmsReceipt> {
        if (down) return Resource.error(IntegrationError.Unavailable("sms"))
        texts += text
        return Resource.success(SmsReceipt("m${texts.size}", "test"))
    }

    fun lastCode(): String = Regex("[0-9]{6}").find(texts.last())!!.value
}

class RecordingEmail : EmailGateway {
    val sent = CopyOnWriteArrayList<EmailTemplate>()
    val keys = CopyOnWriteArrayList<String?>()

    override suspend fun send(
        to: String,
        email: Email,
        idempotencyKey: String?,
    ): Resource<EmailReceipt> {
        sent += email.template
        keys += idempotencyKey
        return Resource.success(EmailReceipt("e", "test"))
    }
}

class FakeCarrier : CarrierPhoneVerification {
    override suspend fun verify(carrierToken: String): Resource<PhoneNumber> =
        PhoneNumber.parse(carrierToken)?.let { Resource.success(it) } ?: Resource.error(IntegrationError.Rejected("no"))
}

/** A phone: a device id and a hardware-like key that signs proofs. */
class Phone(val model: String = "Pixel") {
    val deviceId = DeviceId.new()
    private val pair: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    val publicKey: String = Base64.getEncoder().encodeToString(pair.public.encoded)

    fun sign(payload: String): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(payload.toByteArray())
            sign()
        }

    fun info() = DeviceInfo(deviceId, "android", model, "14", "1.0.0")
}

/** A Twilio Verify stand-in: it makes the code itself, so a test can read it from here the way a person reads it from their phone. */
class FakeVendorVerification : PhoneVerificationGateway {
    override val managesCodes = true
    val code = "246810"
    var started = 0
    var down = false
    var expired = false

    override suspend fun start(to: PhoneNumber, appHash: String?): Resource<Unit> {
        if (down) return Resource.error(IntegrationError.Unavailable("Twilio"))
        started++
        return Resource.success(Unit)
    }

    override suspend fun check(to: PhoneNumber, code: String): Resource<CodeCheck> =
        Resource.success(
            when {
                expired -> CodeCheck.EXPIRED
                code == this.code -> CodeCheck.APPROVED
                else -> CodeCheck.DENIED
            },
        )
}

/** The storage ports the use cases need: all [MemoryStore] by default, or the PostgreSQL data layer. */
class IdentityPorts(
    val transactor: Transactor,
    val accounts: AccountRepository,
    val providers: ProviderRepository,
    val devices: DeviceRepository,
    val sessions: SessionRepository,
    val store: IdentityStore,
)

fun MemoryStore.ports() = IdentityPorts(this, this, this, this, this, this)

class Harness(
    attestation: AttestationMode = AttestationMode.LOG,
    attested: Boolean = true,
    vendor: PhoneVerificationGateway = NoVendorVerification,
    ports: IdentityPorts? = null,
) {
    val store = MemoryStore()
    private val p = ports ?: store.ports()
    val cache = MemoryCache()
    val bus = MemoryBus()
    val sms = RecordingSms()
    val email = RecordingEmail()
    private val clock = Clock.systemUTC()
    private val config = IdentityConfig(attestation = attestation)
    private val secrets =
        object : Secrets {
            override fun get(name: String): String? = null
        }
    private val tokens = Ed25519Tokens(secrets, local = true, clock = clock)
    private val phones = KeyedPhoneProtection(secrets, local = true)
    private val keys = EcdsaDeviceKeyVerifier()
    private val opener = SessionOpener(tokens, p.sessions, cache, clock)
    private val proofs = DeviceProofs(keys, clock, config.proofWindow)
    private val ownership =
        PhoneOwnership(p.transactor, p.accounts, p.sessions, p.store, cache, phones, bus, config, clock)
    private val attestationVerifier =
        object : AttestationVerifier {
            override suspend fun verify(
                platform: String,
                deviceId: DeviceId,
                attestation: String?,
            ) = attested
        }

    val issueNonce = IssueNonce(cache)
    val signInWithProvider =
        SignInWithProvider(
            p.transactor,
            cache,
            FakeSocial(),
            p.accounts,
            p.providers,
            p.devices,
            p.store,
            opener,
            proofs,
            tokens,
            clock,
        )
    val startPhone = StartPhoneVerification(cache, p.accounts, phones, sms, config, vendor)
    val verifyCode = VerifyPhoneCode(cache, p.accounts, phones, ownership, config, vendor)
    val verifyCarrier = VerifyCarrierToken(FakeCarrier(), p.accounts, ownership)
    val registerDevice =
        RegisterDevice(
            p.transactor,
            p.accounts,
            p.devices,
            p.providers,
            p.store,
            cache,
            phones,
            keys,
            attestationVerifier,
            opener,
            bus,
            email,
            config,
            clock,
        )
    val refresh = RefreshSession(p.transactor, p.sessions, p.accounts, p.devices, p.store, cache, opener, proofs)
    val signOut = SignOut(p.transactor, p.sessions, p.store, cache, bus)
    val verifyToken = VerifyAccessToken(tokens, cache)
    val verifyTokenAlongside = VerifyAccessTokenAlongside(tokens, cache)
    val overview = GetSecurityOverview(p.accounts, p.providers, p.devices)
    val link = LinkProvider(p.transactor, cache, FakeSocial(), p.accounts, p.providers, p.store, clock)
    val unlink = UnlinkProvider(p.transactor, p.accounts, p.providers, p.store)
    val delete =
        RequestAccountDeletion(p.transactor, p.accounts, p.sessions, emptyList(), p.store, cache, bus, config, clock)
    val recovery = RequestSupportRecovery(p.accounts, p.providers, p.store, cache, phones, email)
    val release = RequestPhoneRelease(cache, p.accounts, p.providers, phones, p.store, email, config, clock)

    suspend fun nonce(): String = issueNonce().getOrNull()!!

    suspend fun signIn(
        sub: String,
        phone: Phone,
        proofFor: Phone? = null,
        email: String? = "$sub@example.com",
    ): SignInResult {
        val nonce = nonce()
        val proof =
            proofFor?.let {
                val timestamp = clock.instant()
                DeviceProof(timestamp, it.sign(DeviceProofs.signInPayload(nonce, DeviceProof(timestamp, ByteArray(0)))))
            }
        val command =
            SignInCommand(
                SocialProvider.GOOGLE,
                "dev:$sub:${email.orEmpty()}:Test $sub",
                nonce,
                null,
                phone.info(),
                proof,
            )
        return (signInWithProvider(command) as Resource.Success).data
    }

    suspend fun principalOf(token: String): Principal = (verifyToken(token) as Resource.Success).data

    /** Sign-up through to an active account on [phone]; returns the number, the session and the registered device. */
    suspend fun signUp(
        sub: String,
        phone: Phone,
        number: String,
        email: String? = "$sub@example.com",
    ): RegisteredDevice {
        val onboarding = principalOf(signIn(sub, phone, email = email).session.accessToken)
        val started = (startPhone(StartPhoneCommand(onboarding, PhoneNumber.parse(number)!!)) as Resource.Success).data
        verifyCode(VerifyCodeCommand(onboarding, started.verificationId, sms.lastCode()))
        return register(onboarding, phone)
    }

    suspend fun register(onboarding: Principal, phone: Phone): RegisteredDevice =
        (
            registerDevice(
                RegisterDeviceCommand(
                    onboarding,
                    phone.deviceId,
                    phone.publicKey,
                    "ES256",
                    "fcm:${phone.model}",
                    "attest",
                    "android",
                    phone.model,
                    "1.0.0",
                ),
            ) as Resource.Success
        ).data

    fun step(result: SignInResult): NextStep = result.next
}
