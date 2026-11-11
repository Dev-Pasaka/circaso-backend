package com.circaso.identity.presentation.services

import com.circaso.identity.api.Principal
import com.circaso.identity.domain.models.PinVault
import com.circaso.identity.domain.models.SaveVaultCommand
import com.circaso.identity.domain.usecases.GetVaultStatus
import com.circaso.identity.domain.usecases.OpenVault
import com.circaso.identity.domain.usecases.RemoveVault
import com.circaso.identity.domain.usecases.SaveVault
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/** The PIN Vault: its status, opening it, saving it, removing it. */
class VaultService(
    private val getVaultStatus: GetVaultStatus,
    private val openVault: OpenVault,
    private val saveVault: SaveVault,
    private val removeVault: RemoveVault,
) {
    suspend fun status(userId: UserId): Resource<PinVault?> = getVaultStatus(userId)

    suspend fun open(principal: Principal): Resource<PinVault> = openVault(principal)

    suspend fun save(command: SaveVaultCommand): Resource<PinVault> = saveVault(command)

    suspend fun remove(principal: Principal): Resource<Unit> = removeVault(principal)
}
