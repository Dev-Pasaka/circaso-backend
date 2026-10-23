package com.circaso.identity.presentation.services

import com.circaso.identity.api.Principal
import com.circaso.identity.domain.models.RefreshCommand
import com.circaso.identity.domain.models.RefreshedSession
import com.circaso.identity.domain.models.SignInCommand
import com.circaso.identity.domain.models.SignInResult
import com.circaso.identity.domain.ports.PublicKey
import com.circaso.identity.domain.usecases.GetPublicKeys
import com.circaso.identity.domain.usecases.IssueNonce
import com.circaso.identity.domain.usecases.RefreshSession
import com.circaso.identity.domain.usecases.SignInWithProvider
import com.circaso.identity.domain.usecases.SignOut
import com.circaso.shared.Resource

/** Nonce, social sign-in, refresh, sign-out and the public keys. */
class AuthService(
    private val issueNonce: IssueNonce,
    private val signInWithProvider: SignInWithProvider,
    private val refreshSession: RefreshSession,
    private val signOut: SignOut,
    private val getPublicKeys: GetPublicKeys,
) {
    suspend fun nonce(): Resource<String> = issueNonce()
    suspend fun signIn(command: SignInCommand): Resource<SignInResult> = signInWithProvider(command)
    suspend fun refresh(command: RefreshCommand): Resource<RefreshedSession> = refreshSession(command)
    suspend fun signOut(principal: Principal): Resource<Unit> = signOut.invoke(principal)
    suspend fun publicKeys(): Resource<List<PublicKey>> = getPublicKeys()
}
