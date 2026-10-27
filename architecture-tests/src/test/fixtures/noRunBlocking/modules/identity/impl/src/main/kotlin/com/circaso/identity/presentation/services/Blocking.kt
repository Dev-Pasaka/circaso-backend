package com.circaso.identity.presentation.services

import kotlinx.coroutines.runBlocking

class BlockingService {
    fun now() = runBlocking { 1 }
}
