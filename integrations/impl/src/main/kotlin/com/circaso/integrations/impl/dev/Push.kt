package com.circaso.integrations.impl.dev

import com.circaso.integrations.api.PushGateway
import com.circaso.integrations.api.PushMessage
import com.circaso.integrations.api.PushReceipt
import com.circaso.integrations.api.PushTarget
import com.circaso.shared.Resource
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Local runs: prints the title to the console. */
class LogPush : PushGateway {
    private val log = LoggerFactory.getLogger(LogPush::class.java)

    override suspend fun send(target: PushTarget, message: PushMessage): Resource<PushReceipt> {
        log.info("push to {}: {}", target.platform, message.title)
        return Resource.success(PushReceipt(UUID.randomUUID().toString(), "log"))
    }
}

/** Tests: records every push for assertions. */
class FakePush : PushGateway {
    data class Sent(val target: PushTarget, val message: PushMessage)

    val sent: MutableList<Sent> = CopyOnWriteArrayList()

    override suspend fun send(target: PushTarget, message: PushMessage): Resource<PushReceipt> {
        sent += Sent(target, message)
        return Resource.success(PushReceipt("fake-${sent.size}", "fake"))
    }
}
