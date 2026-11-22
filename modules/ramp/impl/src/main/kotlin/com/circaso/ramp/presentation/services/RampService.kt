package com.circaso.ramp.presentation.services

import com.circaso.ramp.api.RampOrderId
import com.circaso.ramp.domain.models.OrderView
import com.circaso.ramp.domain.models.PageCursor
import com.circaso.ramp.domain.models.Quote
import com.circaso.ramp.domain.models.QuoteCommand
import com.circaso.ramp.domain.models.RampStatus
import com.circaso.ramp.domain.models.StartOnRampCommand
import com.circaso.ramp.domain.usecases.CancelOrder
import com.circaso.ramp.domain.usecases.CreateQuote
import com.circaso.ramp.domain.usecases.GetOrder
import com.circaso.ramp.domain.usecases.GetRampStatus
import com.circaso.ramp.domain.usecases.ListOrders
import com.circaso.ramp.domain.usecases.StartOnRamp
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/** What the app asks the ramp (R1–R6). No logic of its own: each method is one use case. */
class RampService(
    private val getStatus: GetRampStatus,
    private val createQuote: CreateQuote,
    private val startOnRamp: StartOnRamp,
    private val getOrder: GetOrder,
    private val listOrders: ListOrders,
    private val cancelOrder: CancelOrder,
) {
    suspend fun status(userId: UserId, network: Network): Resource<RampStatus> = getStatus(userId, network)

    suspend fun quote(command: QuoteCommand): Resource<Quote> = createQuote(command)

    suspend fun buy(command: StartOnRampCommand): Resource<OrderView> = startOnRamp(command)

    suspend fun order(userId: UserId, orderId: RampOrderId): Resource<OrderView> = getOrder(userId, orderId)

    suspend fun orders(
        userId: UserId,
        network: Network,
        limit: Int,
        after: PageCursor?,
    ): Resource<List<OrderView>> = listOrders(userId, network, limit, after)

    suspend fun cancel(userId: UserId, orderId: RampOrderId): Resource<OrderView> = cancelOrder(userId, orderId)
}
