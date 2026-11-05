package com.circaso.spaces.domain.support

import com.circaso.shared.Asset
import com.circaso.spaces.api.FramePrice
import com.circaso.spaces.domain.errors.SpacesError
import com.circaso.spaces.domain.models.Price
import com.circaso.spaces.domain.models.Visibility
import com.circaso.spaces.domain.repositories.SpaceRepository
import java.math.BigDecimal

/** The rules a space's details follow, shared by create (SP2) and update (SP5). */
object SpaceRules {
    fun details(
        name: String,
        about: String,
        handle: String,
        config: SpacesConfig,
    ): SpacesError? =
        when {
            name.isBlank() || name.trim().length > config.nameMax -> {
                SpacesError.DetailsInvalid("name", "1 to ${config.nameMax} characters")
            }

            about.trim().length > config.aboutMax -> {
                SpacesError.DetailsInvalid("about", "at most ${config.aboutMax} characters")
            }

            !Handles.isValid(handle) -> {
                SpacesError.HandleInvalid
            }

            else -> {
                null
            }
        }

    /** A members-only space has a price in range; the others have none. */
    fun price(
        visibility: Visibility,
        price: Price?,
        config: SpacesConfig,
    ): SpacesError? =
        when {
            visibility != Visibility.MEMBERS_ONLY && price != null -> {
                SpacesError.PriceInvalid
            }

            visibility == Visibility.MEMBERS_ONLY && price == null -> {
                SpacesError.PriceInvalid
            }

            price != null && price.amountMinor !in config.priceMinMinor..config.priceMaxMinor -> {
                SpacesError.PriceInvalid
            }

            else -> {
                null
            }
        }

    /** Three free handles like [handle] (SN1b's chips). */
    suspend fun freeHandles(
        spaces: SpaceRepository,
        handle: String,
        name: String,
    ): List<String> {
        val candidates = Handles.candidates(handle, name)
        val taken = spaces.takenHandles(candidates)
        return candidates.filterNot { it in taken }.take(SUGGESTIONS)
    }

    private const val SUGGESTIONS = 3
}

/** USDC minor units as the frames and the API carry them: exact, and in whole units for display. */
fun usdcAmount(minor: Long): String =
    BigDecimal.valueOf(minor, Asset.USDC.decimals).stripTrailingZeros().toPlainString()

fun framePrice(price: Price): FramePrice =
    FramePrice(Asset.USDC.name, price.amountMinor.toString(), usdcAmount(price.amountMinor), price.period.name)
