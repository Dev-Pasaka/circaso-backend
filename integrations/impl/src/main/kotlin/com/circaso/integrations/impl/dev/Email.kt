package com.circaso.integrations.impl.dev

import com.circaso.integrations.api.EmailGateway
import com.circaso.integrations.api.EmailReceipt
import com.circaso.integrations.api.EmailTemplate
import com.circaso.shared.Resource
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Local runs: prints the template name to the console; the address and variables stay out of the log. */
class LogEmail : EmailGateway {
    private val log = LoggerFactory.getLogger(LogEmail::class.java)

    override suspend fun send(to: String, template: EmailTemplate, variables: Map<String, String>, idempotencyKey: String?): Resource<EmailReceipt> {
        log.info("email: {}", template)
        return Resource.success(EmailReceipt(UUID.randomUUID().toString(), "log"))
    }
}

/** Tests: records every email for assertions. */
class FakeEmail : EmailGateway {
    data class Sent(val to: String, val template: EmailTemplate, val variables: Map<String, String>, val idempotencyKey: String? = null)

    val sent: MutableList<Sent> = CopyOnWriteArrayList()

    override suspend fun send(to: String, template: EmailTemplate, variables: Map<String, String>, idempotencyKey: String?): Resource<EmailReceipt> {
        sent += Sent(to, template, variables, idempotencyKey)
        return Resource.success(EmailReceipt("fake-${sent.size}", "fake"))
    }
}
