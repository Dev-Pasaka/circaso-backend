package com.circaso.ramp.presentation.services

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.models.CallbackView
import com.circaso.ramp.domain.models.CodeSent
import com.circaso.ramp.domain.models.Earnings
import com.circaso.ramp.domain.models.KycView
import com.circaso.ramp.domain.models.MerchantOverview
import com.circaso.ramp.domain.models.OnlineView
import com.circaso.ramp.domain.models.Order
import com.circaso.ramp.domain.models.PageCursor
import com.circaso.ramp.domain.models.PaymentAccountCommand
import com.circaso.ramp.domain.models.Period
import com.circaso.ramp.domain.models.ProfileCommand
import com.circaso.ramp.domain.models.SignedIn
import com.circaso.ramp.domain.models.Statement
import com.circaso.ramp.domain.usecases.ApplyAsMerchant
import com.circaso.ramp.domain.usecases.ChangePin
import com.circaso.ramp.domain.usecases.GetEarnings
import com.circaso.ramp.domain.usecases.GetMerchant
import com.circaso.ramp.domain.usecases.GetStatement
import com.circaso.ramp.domain.usecases.GoOffline
import com.circaso.ramp.domain.usecases.GoOnline
import com.circaso.ramp.domain.usecases.ListMerchantOrders
import com.circaso.ramp.domain.usecases.RequestSignInCode
import com.circaso.ramp.domain.usecases.ResolveSession
import com.circaso.ramp.domain.usecases.RotateCallback
import com.circaso.ramp.domain.usecases.SignOut
import com.circaso.ramp.domain.usecases.StartMerchantKyc
import com.circaso.ramp.domain.usecases.StorePaymentAccount
import com.circaso.ramp.domain.usecases.VerifySignInCode
import com.circaso.shared.Network
import com.circaso.shared.Resource

/** The portal's sign-in (MA1–MA3) and who a request is from. No logic of its own: each method is one use case. */
class MerchantAuthService(
    private val requestSignInCode: RequestSignInCode,
    private val verifySignInCode: VerifySignInCode,
    private val resolveSession: ResolveSession,
    private val signOutUseCase: SignOut,
) {
    suspend fun requestCode(email: String, clientAddress: String): Resource<CodeSent> =
        requestSignInCode(email, clientAddress)

    suspend fun verify(
        email: String,
        code: String,
        network: Network,
    ): Resource<SignedIn> = verifySignInCode(email, code, network)

    suspend fun merchantOf(token: String?): Resource<MerchantId> = resolveSession(token)

    suspend fun signOut(token: String?): Resource<Unit> = signOutUseCase(token)
}

/** What a signed-in merchant asks the ramp (RM1–RM15). No logic of its own: each method is one use case. */
class MerchantService(
    private val getMerchant: GetMerchant,
    private val applyAsMerchant: ApplyAsMerchant,
    private val storePaymentAccount: StorePaymentAccount,
    private val rotateCallbackUseCase: RotateCallback,
    private val goOnlineUseCase: GoOnline,
    private val goOfflineUseCase: GoOffline,
    private val changePinUseCase: ChangePin,
    private val listMerchantOrders: ListMerchantOrders,
    private val getEarnings: GetEarnings,
    private val getStatement: GetStatement,
    private val startMerchantKyc: StartMerchantKyc,
) {
    suspend fun startKyc(merchantId: MerchantId, network: Network): Resource<KycView> =
        startMerchantKyc(merchantId, network)

    suspend fun merchant(merchantId: MerchantId, network: Network): Resource<MerchantOverview> =
        getMerchant(merchantId, network)

    suspend fun apply(
        merchantId: MerchantId,
        network: Network,
        profile: ProfileCommand,
    ): Resource<MerchantOverview> = applyAsMerchant(merchantId, network, profile)

    suspend fun storePayments(
        merchantId: MerchantId,
        network: Network,
        command: PaymentAccountCommand,
    ): Resource<CallbackView> = storePaymentAccount(merchantId, network, command)

    suspend fun rotateCallback(merchantId: MerchantId, network: Network): Resource<CallbackView> =
        rotateCallbackUseCase(merchantId, network)

    suspend fun goOnline(
        merchantId: MerchantId,
        network: Network,
        pin: String,
        minutes: Int,
    ): Resource<OnlineView> = goOnlineUseCase(merchantId, network, pin, minutes)

    suspend fun goOffline(merchantId: MerchantId): Resource<OnlineView> = goOfflineUseCase(merchantId)

    suspend fun changePin(
        merchantId: MerchantId,
        currentPin: String,
        newPin: String,
    ): Resource<Unit> = changePinUseCase(merchantId, currentPin, newPin)

    suspend fun orders(
        merchantId: MerchantId,
        network: Network,
        limit: Int,
        after: PageCursor?,
    ): Resource<List<Order>> = listMerchantOrders(merchantId, network, limit, after)

    suspend fun earnings(
        merchantId: MerchantId,
        network: Network,
        period: Period?,
    ): Resource<Earnings> = getEarnings(merchantId, network, period)

    suspend fun statement(
        merchantId: MerchantId,
        network: Network,
        period: Period?,
        limit: Int,
        after: PageCursor?,
    ): Resource<Statement> = getStatement(merchantId, network, period, limit, after)
}
