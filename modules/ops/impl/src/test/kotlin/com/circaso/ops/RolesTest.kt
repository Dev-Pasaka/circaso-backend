package com.circaso.ops

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.OpsGroup
import com.circaso.ops.domain.models.Permission
import com.circaso.shared.StaffRole
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Every staff route, called as every role (decision 2026-11-08): a role the [Permission] table doesn't allow is
 * refused with 403 `ops.role_forbidden` before anything is read; a role it allows gets through to the route. This is
 * the server-side gate; the portal's menu only mirrors it.
 */
class RolesTest {
    private val harness = OpsHarness()
    private val user = harness.accounts.add()
    private val ticket = harness.desk.open(user)
    private val chat = ChatId(Uuid7.generate())
    private val transfer = Uuid7.generate()
    private val otherStaff = Uuid7.generate()

    init {
        harness.groups.groups[chat] = OpsGroup(chat, 3, user, EPOCH)
    }

    private data class Call(
        val method: String,
        val path: String,
        val permission: Permission,
        val body: String? = null,
    )

    private val calls =
        listOf(
            Call("GET", "/v1/ops/me", Permission.USERS_READ),
            Call("GET", "/v1/ops/dashboard", Permission.DASHBOARD_USERS),
            Call("GET", "/v1/ops/users?q=", Permission.USERS_READ),
            Call("GET", "/v1/ops/users/$user", Permission.USERS_READ),
            Call("GET", "/v1/ops/users/$user/groups", Permission.USERS_READ),
            Call("GET", "/v1/ops/users/$user/devices", Permission.USERS_DEVICES),
            Call("GET", "/v1/ops/users/$user/tickets", Permission.USERS_TICKETS),
            Call("GET", "/v1/ops/users/$user/wallets", Permission.USERS_MONEY),
            Call("GET", "/v1/ops/users/$user/transfers", Permission.USERS_MONEY),
            Call("POST", "/v1/ops/users/$user/suspend", Permission.USERS_SUSPEND, """{"reason":"fraud report"}"""),
            Call("POST", "/v1/ops/users/$user/unsuspend", Permission.USERS_SUSPEND, """{"reason":"cleared"}"""),
            Call("GET", "/v1/ops/transactions", Permission.TRANSACTIONS_READ),
            Call("GET", "/v1/ops/transactions/$transfer", Permission.TRANSACTIONS_READ),
            Call("GET", "/v1/ops/finance", Permission.FINANCE_READ),
            Call("GET", "/v1/ops/ai-usage", Permission.AI_USAGE_READ),
            Call("GET", "/v1/ops/groups", Permission.GROUPS_READ),
            Call("GET", "/v1/ops/groups/${chat.value}", Permission.GROUPS_READ),
            Call("GET", "/v1/ops/tickets", Permission.TICKETS_READ),
            Call("GET", "/v1/ops/tickets/$ticket", Permission.TICKETS_READ),
            Call("POST", "/v1/ops/tickets/$ticket/replies", Permission.TICKETS_WRITE, """{"body":"On it."}"""),
            Call("POST", "/v1/ops/tickets/$ticket/notes", Permission.TICKETS_WRITE, """{"body":"Checked Circle."}"""),
            Call("POST", "/v1/ops/tickets/$ticket/assign", Permission.TICKETS_WRITE, """{"staffId":null}"""),
            Call("POST", "/v1/ops/tickets/$ticket/status", Permission.TICKETS_WRITE, """{"status":"RESOLVED"}"""),
            Call("GET", "/v1/ops/staff", Permission.STAFF_MANAGE),
            Call("POST", "/v1/ops/staff", Permission.STAFF_MANAGE, """{"email":"new@circaso.com","role":"SUPPORT"}"""),
            Call("PATCH", "/v1/ops/staff/$otherStaff", Permission.STAFF_MANAGE, """{"active":false}"""),
            Call("GET", "/v1/ops/audit", Permission.AUDIT_READ),
            Call("GET", "/v1/ops/support-accounts", Permission.SUPPORT_ACCOUNTS_MANAGE),
            Call(
                "POST",
                "/v1/ops/support-accounts",
                Permission.SUPPORT_ACCOUNTS_MANAGE,
                """{"displayName":"Circaso","handle":"circaso"}""",
            ),
        )

    private suspend fun ApplicationTestBuilder.send(call: Call, role: StaffRole?): HttpResponse {
        val build: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            role?.let { header(OpsHarness.ROLE_HEADER, it.name) }
            call.body?.let {
                contentType(ContentType.Application.Json)
                setBody(it)
            }
        }
        return when (call.method) {
            "GET" -> client.get(call.path, build)
            "POST" -> client.post(call.path, build)
            else -> client.patch(call.path, build)
        }
    }

    @Test
    fun `each role is refused exactly what the permission table doesn't give it`() =
        testApplication {
            application { harness.install(this) }

            for (call in calls) {
                for (role in StaffRole.entries) {
                    val response = send(call, role)
                    if (call.permission.allows(role)) {
                        assertNotEquals(
                            HttpStatusCode.Forbidden,
                            response.status,
                            "$role should reach ${call.method} ${call.path}: ${response.bodyAsText()}",
                        )
                    } else {
                        assertEquals(
                            HttpStatusCode.Forbidden,
                            response.status,
                            "$role must not reach ${call.method} ${call.path}",
                        )
                        assertTrue(response.bodyAsText().contains("ops.role_forbidden"))
                    }
                }
            }
        }

    @Test
    fun `nobody signed in is refused every route with ops sign_in_required`() =
        testApplication {
            application { harness.install(this) }

            for (call in calls) {
                val response = send(call, role = null)
                assertEquals(HttpStatusCode.Unauthorized, response.status, "${call.method} ${call.path}")
                assertTrue(response.bodyAsText().contains("ops.sign_in_required"))
            }
        }

    @Test
    fun `the table gives SUPPORT no money and FINANCE no people's devices, tickets or suspensions`() {
        val support = Permission.of(StaffRole.SUPPORT)
        val finance = Permission.of(StaffRole.FINANCE)

        assertTrue(
            support.none {
                it in
                    setOf(
                        Permission.USERS_MONEY,
                        Permission.TRANSACTIONS_READ,
                        Permission.FINANCE_READ,
                        Permission.AI_USAGE_READ,
                    )
            },
        )
        assertTrue(
            finance.none {
                it in
                    setOf(
                        Permission.USERS_DEVICES,
                        Permission.USERS_TICKETS,
                        Permission.USERS_SUSPEND,
                        Permission.TICKETS_READ,
                    )
            },
        )
        assertTrue(finance.none { it == Permission.STAFF_MANAGE || it == Permission.AUDIT_READ })
        assertEquals(Permission.entries.toSet(), Permission.of(StaffRole.ADMIN).toSet())
    }

    @Test
    fun `me lists the caller's permissions by their wire names`() =
        testApplication {
            application { harness.install(this) }
            val body = client.get("/v1/ops/me") { header(OpsHarness.ROLE_HEADER, "FINANCE") }.bodyAsText()

            assertTrue(body.contains("\"finance.read\""))
            assertTrue(!body.contains("\"tickets.read\""))
            assertTrue(body.contains("\"role\":\"FINANCE\""))
        }

    @Suppress("unused")
    private val unusedUser: UserId = user
}
