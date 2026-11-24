package com.circaso.integrations.impl.dev

import com.circaso.integrations.api.Email
import com.circaso.integrations.api.EmailGateway
import com.circaso.integrations.api.EmailReceipt
import com.circaso.shared.Resource
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Local runs: prints the template name to the console; the address and variables stay out of the log. The one
 * exception is a merchant's sign-in code, printed as `LogSms` prints a phone's code: with no real email there is no
 * other way for a developer to read it. A real provider never logs it.
 */
class LogEmail : EmailGateway {
    private val log = LoggerFactory.getLogger(LogEmail::class.java)

    override suspend fun send(
        to: String,
        email: Email,
        idempotencyKey: String?,
    ): Resource<EmailReceipt> {
        if (email is Email.MerchantSignInCode) {
            log.info("email: {} code={} (valid {} min)", email.template, email.code, email.minutes)
        } else if (email is Email.MerchantSecurityCode) {
            log.info("email: {} code={} (valid {} min)", email.template, email.code, email.minutes)
        } else if (email is Email.MerchantSecurityAlert) {
            // Local only, like the codes: the "This wasn't me" link can't be followed any other way on a laptop.
            log.info("email: {} lock={}", email.template, email.lockUrl)
        } else {
            log.info("email: {}", email.template)
        }
        return Resource.success(EmailReceipt(UUID.randomUUID().toString(), "log"))
    }
}

/** Tests: records every email for assertions. */
class FakeEmail : EmailGateway {
    data class Sent(val to: String, val email: Email, val idempotencyKey: String? = null)

    val sent: MutableList<Sent> = CopyOnWriteArrayList()

    override suspend fun send(
        to: String,
        email: Email,
        idempotencyKey: String?,
    ): Resource<EmailReceipt> {
        sent += Sent(to, email, idempotencyKey)
        return Resource.success(EmailReceipt("fake-${sent.size}", "fake"))
    }
}
