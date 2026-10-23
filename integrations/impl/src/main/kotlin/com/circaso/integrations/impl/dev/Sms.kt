package com.circaso.integrations.impl.dev

import com.circaso.integrations.api.SmsGateway
import com.circaso.integrations.api.SmsPurpose
import com.circaso.integrations.api.SmsReceipt
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Local runs: prints the text, code included, to the console. The number is masked. */
class LogSms : SmsGateway {
    private val log = LoggerFactory.getLogger(LogSms::class.java)

    override suspend fun send(to: PhoneNumber, text: String, purpose: SmsPurpose): Resource<SmsReceipt> {
        log.info("sms to {} ({}): {}", to.masked(), purpose, text)
        return Resource.success(SmsReceipt(UUID.randomUUID().toString(), "log"))
    }
}

/** Tests: records every message for assertions. */
class FakeSms : SmsGateway {
    data class Sent(val to: PhoneNumber, val text: String, val purpose: SmsPurpose)

    val sent: MutableList<Sent> = CopyOnWriteArrayList()

    override suspend fun send(to: PhoneNumber, text: String, purpose: SmsPurpose): Resource<SmsReceipt> {
        sent += Sent(to, text, purpose)
        return Resource.success(SmsReceipt("fake-${sent.size}", "fake"))
    }
}
