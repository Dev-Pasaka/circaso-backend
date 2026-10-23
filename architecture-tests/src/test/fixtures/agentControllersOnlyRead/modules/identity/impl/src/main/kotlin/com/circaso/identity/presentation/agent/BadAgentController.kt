package com.circaso.identity.presentation.agent

import com.circaso.identity.presentation.services.ProfileService

class BadAgentController(private val profiles: ProfileService) {
    suspend fun show() = profiles.get()
    suspend fun change() = profiles.update()
    suspend fun remove() = profiles.delete()
}
