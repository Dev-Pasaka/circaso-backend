package com.circaso.ramp.presentation.support

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.SignInContext
import com.circaso.ramp.presentation.services.MerchantAuthService
import com.circaso.shared.DomainException
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.WalletNetwork
import com.circaso.web.WALLET_NETWORK_HEADER
import com.circaso.web.respondError
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.server.routing.intercept
import io.ktor.util.AttributeKey
import kotlinx.coroutines.withContext

/** The merchant portal's session header (decision 2026-11-22): never `Authorization`, which is an app token's. */
const val MERCHANT_SESSION_HEADER = "X-Merchant-Session"

private val MerchantKey = AttributeKey<MerchantId>("circaso.ramp.merchant")

/** The signed-in merchant. Only valid inside [merchantSession], which refuses a call without one. */
fun ApplicationCall.merchantId(): MerchantId =
    attributes.getOrNull(MerchantKey) ?: throw DomainException(RampError.SessionInvalid)

/**
 * The network a portal request is about: `X-Wallet-Network` (`MAINNET` or `TESTNET`), the testnet when it is absent
 * or anything else. A merchant account is on both; what the header picks is which float wallet and which settings.
 */
fun ApplicationCall.merchantNetwork(): Network = Network.parse(request.header(WALLET_NETWORK_HEADER)) ?: Network.TESTNET

/**
 * A route group for a signed-in merchant: the `X-Merchant-Session` token must be one of the ramp's own live sessions
 * (only its SHA-256 is stored), or the answer is `ramp.session_invalid`. The handler runs with the request's network
 * in its coroutine context, as the app's money routes do.
 */
// Route-scoped plugins can't stop a route after answering; `intercept` plus `finish()` can.
@Suppress("DEPRECATION")
fun Route.merchantSession(auth: MerchantAuthService, build: Route.() -> Unit): Route {
    val node =
        createChild(
            object : RouteSelector() {
                override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int) =
                    RouteSelectorEvaluation.Transparent

                override fun toString() = "(merchant session)"
            },
        )
    node.intercept(ApplicationCallPipeline.Plugins) {
        when (val merchant = auth.merchantOf(call.request.header(MERCHANT_SESSION_HEADER))) {
            is Resource.Error -> {
                call.respondError(merchant.error)
                finish()
            }

            is Resource.Success -> {
                call.attributes.put(MerchantKey, merchant.data)
                withContext(WalletNetwork(call.merchantNetwork())) { proceed() }
            }
        }
    }
    node.build()
    return node
}

/**
 * The caller's address for the sign-in limits: Cloudflare's `CF-Connecting-IP` when it is there, else the socket's
 * peer, the same source the gateway's own rate limits trust. No other forwarding header is believed.
 */
fun ApplicationCall.clientAddress(): String =
    request.header("CF-Connecting-IP")?.trim()?.takeIf { it.isNotEmpty() } ?: request.local.remoteHost

/**
 * Where a sign-in came from, as far as the request already says (2026-11-24; no new tracking): the browser and system
 * read from `User-Agent`, and the country (and city) Cloudflare saw, which the portal's worker passes on as
 * `X-Client-Country` / `X-Client-City` (else `CF-IPCountry`). Only used in the merchant's own sign-in email.
 */
fun ApplicationCall.signInContext(): SignInContext {
    val agent = request.header("User-Agent").orEmpty()
    val country =
        (request.header("X-Client-Country") ?: request.header("CF-IPCountry"))
            ?.trim()
            ?.takeIf { it.length == 2 && it != "XX" && it != "T1" }
    val city =
        request
            .header("X-Client-City")
            ?.trim()
            ?.take(MAX_CITY)
            ?.takeIf { it.isNotEmpty() }
    val place =
        listOfNotNull(
            city,
            country?.let {
                java.util.Locale
                    .of("", it)
                    .getDisplayCountry(java.util.Locale.ENGLISH)
            },
        ).joinToString(", ")
            .ifEmpty { "unknown" }
    return SignInContext(
        device = UserAgents.describe(agent),
        location =
            "$place (approximate)".takeIf { place != "unknown" } ?: place,
    )
}

private const val MAX_CITY = 60

/** A browser and system in words ("Chrome on macOS") from a `User-Agent`; never the string itself. */
object UserAgents {
    fun describe(agent: String): String {
        val browser =
            when {
                "Edg/" in agent -> "Edge"
                "OPR/" in agent -> "Opera"
                "Firefox/" in agent -> "Firefox"
                "CriOS/" in agent || "Chrome/" in agent -> "Chrome"
                "Safari/" in agent && "Version/" in agent -> "Safari"
                else -> null
            }
        val system =
            when {
                "iPhone" in agent || "iPad" in agent -> "iOS"
                "Android" in agent -> "Android"
                "Mac OS X" in agent || "Macintosh" in agent -> "macOS"
                "Windows" in agent -> "Windows"
                "CrOS" in agent -> "ChromeOS"
                "Linux" in agent -> "Linux"
                else -> null
            }
        return when {
            browser != null && system != null -> "$browser on $system"
            browser != null -> browser
            system != null -> "a browser on $system"
            else -> "an unknown browser"
        }
    }
}
