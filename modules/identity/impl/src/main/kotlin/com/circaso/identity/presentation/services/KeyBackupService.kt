package com.circaso.identity.presentation.services

import com.circaso.identity.api.Principal
import com.circaso.identity.domain.models.GroupKeyPage
import com.circaso.identity.domain.models.KeyBackupRoot
import com.circaso.identity.domain.models.SaveGroupKeyCommand
import com.circaso.identity.domain.models.SaveKeyBackupRootCommand
import com.circaso.identity.domain.usecases.GetKeyBackupRoot
import com.circaso.identity.domain.usecases.ListGroupKeys
import com.circaso.identity.domain.usecases.RemoveKeyBackup
import com.circaso.identity.domain.usecases.SaveGroupKey
import com.circaso.identity.domain.usecases.SaveKeyBackupRoot
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/** The group key backup: its root, its group keys, turning it off. */
class KeyBackupService(
    private val getKeyBackupRoot: GetKeyBackupRoot,
    private val saveKeyBackupRoot: SaveKeyBackupRoot,
    private val removeKeyBackup: RemoveKeyBackup,
    private val saveGroupKey: SaveGroupKey,
    private val listGroupKeys: ListGroupKeys,
) {
    suspend fun root(userId: UserId): Resource<KeyBackupRoot?> = getKeyBackupRoot(userId)

    suspend fun saveRoot(command: SaveKeyBackupRootCommand): Resource<KeyBackupRoot> = saveKeyBackupRoot(command)

    suspend fun remove(principal: Principal): Resource<Unit> = removeKeyBackup(principal)

    suspend fun putGroupKey(command: SaveGroupKeyCommand): Resource<Unit> = saveGroupKey(command)

    suspend fun groupKeys(
        userId: UserId,
        cursor: String?,
        limit: Int,
    ): Resource<GroupKeyPage> = listGroupKeys(userId, cursor, limit)
}
