package com.circaso.identity.domain.usecases

import com.circaso.identity.domain.models.PinVault
import com.circaso.identity.domain.repositories.VaultRepository
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/**
 * V1 `GET /vault/status`: whether the person has a PIN Vault, and which passkey and version it is at. The presentation
 * answers with none of the blob: reading the status is not an opening, so it logs nothing and pauses nothing.
 */
class GetVaultStatus(private val vaults: VaultRepository) {
    suspend operator fun invoke(userId: UserId): Resource<PinVault?> = Resource.success(vaults.find(userId))
}
