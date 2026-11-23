package com.circaso.ramp.data.support

import com.circaso.integrations.api.CustodySeeds
import com.circaso.integrations.api.CustodySetups
import com.circaso.ramp.domain.models.Order
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.ports.PayoutJournal
import com.circaso.ramp.domain.ports.RampAlerts
import com.circaso.ramp.domain.ports.RampTokens
import com.circaso.ramp.domain.ports.UsdcContracts
import com.circaso.shared.Network
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** What needs a person, as a WARN line for the people on call: ids and reasons, nothing else. */
class LoggedAlerts : RampAlerts {
    private val log = LoggerFactory.getLogger(LoggedAlerts::class.java)

    override fun needsReview(order: Order, reason: String) {
        log.warn("ramp order needs review: order={} merchant={} reason={}", order.orderId, order.merchantId, reason)
    }

    override fun stuck(what: String, reason: String) {
        log.warn("ramp step not done, will be tried again: what={} reason={}", what, reason)
    }
}

/** The payout steps at INFO under `com.circaso.ramp` (`LOG_LEVEL_RAMP`): ids and outcomes only. */
class LoggedPayoutJournal : PayoutJournal {
    private val log = LoggerFactory.getLogger(LoggedPayoutJournal::class.java)

    override fun step(
        what: String,
        id: String,
        outcome: String,
    ) {
        log.info("ramp {}: id={} outcome={}", what, id, outcome)
    }
}

/** Random values from the platform's secure source, and SHA-256. */
class SecureTokens(private val random: SecureRandom = SecureRandom()) : RampTokens {
    override fun sessionToken(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(SESSION_BYTES).also(random::nextBytes))

    override fun digest(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())

    override fun hexDigest(text: String): String = digest(text).joinToString("") { "%02x".format(it) }

    override fun signInCode(): String = "%06d".format(random.nextInt(CODE_BOUND))

    override fun callbackToken(): String =
        (1..CALLBACK_LENGTH).map { BASE62[random.nextInt(BASE62.length)] }.joinToString("")

    private companion object {
        const val SESSION_BYTES = 32
        const val CODE_BOUND = 1_000_000
        const val CALLBACK_LENGTH = 16
        const val BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    }
}

/**
 * The USDC contract on a chain as custody's settings name it: the wallet's tables once it has read them
 * ([CustodySetups], bound by the wallet module), the built-in seeds until then.
 */
class SettingsUsdcContracts(private val setups: () -> CustodySetups?, private val seeds: CustodySeeds) : UsdcContracts {
    override fun contractOn(network: Network, blockchain: String): String? =
        (setups()?.current(network) ?: seeds.seed(network))
            .supported
            .find(blockchain)
            ?.assets
            ?.firstOrNull { it.symbol == RampSettings.ASSET }
            ?.tokenAddress
            ?.takeIf { it.isNotBlank() }
}
