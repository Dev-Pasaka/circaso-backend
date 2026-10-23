package com.circaso.identity.presentation.controllers

import com.circaso.identity.domain.repositories.ProfileRepository
import com.circaso.identity.domain.usecases.UpdateProfile

class BadController(private val update: UpdateProfile, private val profiles: ProfileRepository)
