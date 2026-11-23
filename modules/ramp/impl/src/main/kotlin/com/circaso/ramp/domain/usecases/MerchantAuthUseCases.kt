package com.circaso.ramp.domain.usecases

import com.circaso.integrations.api.Email
import com.circaso.integrations.api.EmailGateway
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.CodeCheck
import com.circaso.ramp.domain.models.CodeSent
import com.circaso.ramp.domain.models.Merchant
import com.circaso.ramp.domain.models.MerchantState
import com.circaso.ramp.domain.models.OfflineReason
import com.circaso.ramp.domain.models.SignedIn
import com.circaso.ramp.domain.ports.RampTokens
import com.circaso.ramp.domain.ports.SignInCodes
import com.circaso.ramp.domain.repositories.MerchantRepository
import com.circaso.ramp.domain.repositories.SessionRepository
import com.circaso.ramp.domain.support.MerchantSignIn
import com.circaso.ramp.domain.support.MerchantViews
import com.circaso.ramp.domain.support.Presence
import com.circaso.shared.Network
import com.circaso.shared.Resource
import java.time.Clock

/**
 * MA1: emails a six-digit code to [email]. The answer is the same whoever asks: any address may sign in (the first
 * verified sign-in creates the merchant account), so nothing here says whether an account exists. A code that can't
 * be sent (the provider is down, or its template isn't set up yet) is an upstream error, never a server failure.
 */
class RequestSignInCode(
    private val codes: SignInCodes,
    private val tokens: RampTokens,
    private val email: EmailGateway,
) {
    suspend operator fun invoke(address: String, clientAddress: String): Resource<CodeSent> {
        val emailKey = tokens.hexDigest(address)
        codes.admit(emailKey, clientAddress)?.let { wait ->
            return Resource.error(RampError.CodeLocked(wait.seconds.coerceAtLeast(1)))
        }
        val code = tokens.signInCode()
        codes.keep(emailKey, tokens.hexDigest("$address:$code"))
        return when (email.send(address, Email.MerchantSignInCode(code, MerchantSignIn.CODE_MINUTES))) {
            is Resource.Error -> Resource.error(RampError.EmailUnavailable)
            is Resource.Success -> Resource.success(CodeSent(MerchantSignIn.RESEND_SECONDS))
        }
    }
}

/**
 * MA2: trades the code for a session. The first verified sign-in of an email creates its merchant account (`NEW`).
 * The session token is 32 random bytes shown once; only its SHA-256 is stored.
 */
class VerifySignInCode(
    private val codes: SignInCodes,
    private val tokens: RampTokens,
    private val merchants: MerchantRepository,
    private val sessions: SessionRepository,
    private val views: MerchantViews,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        address: String,
        code: String,
        network: Network,
    ): Resource<SignedIn> {
        when (codes.check(tokens.hexDigest(address), tokens.hexDigest("$address:$code"))) {
            CodeCheck.MATCH -> Unit
            CodeCheck.WRONG -> return Resource.error(RampError.CodeInvalid)
            CodeCheck.LOCKED -> return Resource.error(RampError.CodeLocked(LOCK_SECONDS))
        }
        val now = clock.instant()
        val merchant = merchants.byEmail(address) ?: create(address)
        val token = tokens.sessionToken()
        val expiresAt = now.plus(MerchantSignIn.SESSION_LIFETIME)
        sessions.insert(tokens.digest(token), merchant.merchantId, now, expiresAt)
        return Resource.success(SignedIn(token, expiresAt, views.overview(merchant, network)))
    }

    private suspend fun create(address: String): Merchant {
        val merchant =
            Merchant(
                merchantId = MerchantId.new(),
                email = address,
                displayName = "",
                contactPhone = "",
                state = MerchantState.NEW,
                callbackKey = null,
                onlineUntil = null,
                lastAssignedAt = null,
                approvedBy = null,
                approvedAt = null,
                createdAt = clock.instant(),
            )
        merchants.insert(merchant)
        // Two first sign-ins at once: the email's one row is the account, whoever wrote it.
        return merchants.byEmail(address) ?: merchant
    }

    private companion object {
        /** A code that used up its tries is dead: the wait is until a new one may be asked for. */
        const val LOCK_SECONDS = MerchantSignIn.RESEND_SECONDS
    }
}

/**
 * Who a portal request is from: the merchant its `X-Merchant-Session` token belongs to, or `ramp.session_invalid`. A
 * session used in the last hour of its twelve is extended.
 */
class ResolveSession(
    private val sessions: SessionRepository,
    private val tokens: RampTokens,
    private val clock: Clock,
) {
    suspend operator fun invoke(token: String?): Resource<MerchantId> {
        if (token.isNullOrBlank()) return Resource.error(RampError.SessionInvalid)
        val now = clock.instant()
        val hash = tokens.digest(token.trim())
        val session = sessions.find(hash, now) ?: return Resource.error(RampError.SessionInvalid)
        if (session.expiresAt.isBefore(now.plus(MerchantSignIn.EXTEND_WITHIN))) {
            sessions.extend(hash, now.plus(MerchantSignIn.SESSION_LIFETIME))
        }
        return Resource.success(session.merchantId)
    }
}

/** MA3: ends the session and takes the merchant offline. A token that is already gone is signed out all the same. */
class SignOut(
    private val sessions: SessionRepository,
    private val tokens: RampTokens,
    private val presence: Presence,
    private val clock: Clock,
) {
    suspend operator fun invoke(token: String?): Resource<Unit> {
        if (token.isNullOrBlank()) return Resource.success(Unit)
        val hash = tokens.digest(token.trim())
        val session = sessions.find(hash, clock.instant()) ?: return Resource.success(Unit)
        sessions.delete(hash)
        presence.goOffline(session.merchantId, OfflineReason.SIGNED_OUT)
        return Resource.success(Unit)
    }
}
