package com.circaso.wallet.presentation.services

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.domain.models.RecipientCheck
import com.circaso.wallet.domain.models.RecipientQuery
import com.circaso.wallet.domain.models.ReminderOutcome
import com.circaso.wallet.domain.usecases.CheckRecipient
import com.circaso.wallet.domain.usecases.RequestSetup

/** Paying a person: W9 whether they can be paid, W10 reminding a contact without a wallet. */
class RecipientService(private val checkRecipient: CheckRecipient, private val requestSetup: RequestSetup) {
    suspend fun check(query: RecipientQuery): Resource<RecipientCheck> = checkRecipient(query)

    suspend fun remind(from: UserId, to: UserId): Resource<ReminderOutcome> = requestSetup(from, to)
}
