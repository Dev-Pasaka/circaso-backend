package com.circaso.integrations.impl.dev

import com.circaso.integrations.api.PushGateway
import com.circaso.integrations.api.PushMessage
import com.circaso.integrations.api.PushReceipt
import com.circaso.integrations.api.PushTarget
import com.circaso.integrations.api.VoipPushGateway
import com.circaso.integrations.api.VoipPushResult
import com.circaso.integrations.api.VoipRing
import com.circaso.integrations.api.VoipRingEnded
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

/** No APNs key configured: every ring goes through [PushGateway] as before. */
class NoVoipPush : VoipPushGateway {
    override val enabled: Boolean = false

    override suspend fun ring(voipToken: String, ring: VoipRing): VoipPushResult = VoipPushResult.FAILED

    override suspend fun ended(voipToken: String, ended: VoipRingEnded): VoipPushResult = VoipPushResult.FAILED
}

/** Tests: records every ring and answers [result]. */
class FakeVoipPush(var result: VoipPushResult = VoipPushResult.SENT) : VoipPushGateway {
    data class Rang(val voipToken: String, val ring: VoipRing)

    val rang: MutableList<Rang> = CopyOnWriteArrayList()
    val endedRings: MutableList<Pair<String, VoipRingEnded>> = CopyOnWriteArrayList()

    override val enabled: Boolean = true

    override suspend fun ring(voipToken: String, ring: VoipRing): VoipPushResult {
        rang += Rang(voipToken, ring)
        return result
    }

    override suspend fun ended(voipToken: String, ended: VoipRingEnded): VoipPushResult {
        endedRings += voipToken to ended
        return result
    }
}
