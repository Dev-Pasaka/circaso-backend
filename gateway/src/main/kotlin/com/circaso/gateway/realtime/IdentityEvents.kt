package com.circaso.gateway.realtime

import com.circaso.identity.api.AccountRelinked
import com.circaso.identity.api.AccountStatus
import com.circaso.identity.api.AccountStatusChanged
import com.circaso.identity.api.DeviceReplaced
import com.circaso.shared.EventEnvelope
import com.circaso.shared.EventHandler
import com.circaso.shared.EventSubscription
import com.circaso.shared.decode

/** The gateway's consumer group on `events:identity`: close sockets that no longer have a right to be open. */
fun gatewayIdentitySubscription(hub: SocketHub) =
    EventSubscription(
        group = "gateway",
        stream = EventEnvelope.streamOf("identity"),
        handlers =
            mapOf(
                DeviceReplaced.TYPE to
                    EventHandler {
                        hub.revoke(
                            it.decode<DeviceReplaced>().let { e ->
                                e.userId
                            },
                            it.decode<DeviceReplaced>().oldDeviceId,
                        )
                    },
                AccountRelinked.TYPE to EventHandler { hub.revoke(it.decode<AccountRelinked>().userId) },
                AccountStatusChanged.TYPE to
                    EventHandler {
                        val event = it.decode<AccountStatusChanged>()
                        if (event.status != AccountStatus.ACTIVE) hub.revoke(event.userId)
                    },
            ),
    )
