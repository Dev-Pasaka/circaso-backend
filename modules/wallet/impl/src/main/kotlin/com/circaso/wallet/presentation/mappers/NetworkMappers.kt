package com.circaso.wallet.presentation.mappers

import com.circaso.wallet.domain.models.NetworkOptions
import com.circaso.wallet.presentation.dto.NetworkOptionDto
import com.circaso.wallet.presentation.dto.NetworksResponse

fun NetworkOptions.toResponse() =
    NetworksResponse(
        networks = networks.map { NetworkOptionDto(it.network.name, it.walletStatus.name) },
        default = default.name,
    )
