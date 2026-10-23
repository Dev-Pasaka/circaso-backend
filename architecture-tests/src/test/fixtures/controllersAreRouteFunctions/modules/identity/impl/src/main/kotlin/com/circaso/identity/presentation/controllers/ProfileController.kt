package com.circaso.identity.presentation.controllers

import com.circaso.identity.presentation.services.ProfileService

// A class, not a 'fun Route.profileController(service)': breaks the rule.
class ProfileController(private val profiles: ProfileService) {
    suspend fun get() = profiles
}
