package com.circaso.ops

import com.circaso.ops.domain.models.AuditOutcome
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.StaffRole
import com.circaso.shared.Uuid7
import com.circaso.wallet.api.OpsFeeStaff
import com.circaso.wallet.api.OpsNewFeeDestination
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.math.BigInteger
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Circaso's fee wallets on the ops routes (decision 2026-11-18): FINANCE and ADMIN read, only ADMIN adds, removes and
 * withdraws; every write needs what the contract says it needs and lands in the audit log with who, why, the network,
 * the chain, the amount and the destination.
 */
class FeeWalletRoutesTest {
    private val harness = OpsHarness()
    private val treasury = FakeFeeWallets.TREASURY

    private companion object {
        const val WITHDRAWALS = "/v1/ops/fee-wallets/withdrawals"
        const val UINT128 = "340282366920938463463374607431768211455"
    }

    private fun HttpRequestBuilder.signedIn(role: StaffRole, key: String? = "key-1") {
        header(OpsHarness.ROLE_HEADER, role.name)
        key?.let { header("Idempotency-Key", it) }
    }

    private suspend fun ApplicationTestBuilder.read(path: String, role: StaffRole = StaffRole.FINANCE): HttpResponse =
        client.get(path) { signedIn(role, key = null) }

    private suspend fun ApplicationTestBuilder.write(
        path: String,
        body: String,
        role: StaffRole = StaffRole.ADMIN,
        key: String? = "key-1",
    ): HttpResponse =
        client.post(path) {
            signedIn(role, key)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun destinationBody(reason: String = "Company treasury, approved by the board") =
        """{"network":"MAINNET","blockchain":"BASE","address":"$treasury","label":"Treasury","reason":"$reason"}"""

    private fun withdrawalBody(
        destination: String,
        amount: String = "\"2500000\"",
        reason: String = "Monthly sweep",
        network: String = "MAINNET",
    ) = """{"network":"$network","blockchain":"BASE","asset":"USDC","amountMinor":$amount,""" +
        """"destinationId":"$destination","reason":"$reason"}"""

    private fun lines(action: String) = harness.audit.entries.filter { it.action == action }

    @Test
    fun `finance sees the wallets, the destinations and the withdrawals, and support sees none of it`() =
        testApplication {
            application { harness.install(this) }

            for (path in listOf("?network=MAINNET", "/destinations", "/withdrawals")) {
                assertEquals(HttpStatusCode.OK, read("/v1/ops/fee-wallets$path", StaffRole.FINANCE).status, path)
                assertEquals(HttpStatusCode.OK, read("/v1/ops/fee-wallets$path", StaffRole.ADMIN).status, path)
                val support = read("/v1/ops/fee-wallets$path", StaffRole.SUPPORT)
                assertEquals(HttpStatusCode.Forbidden, support.status, path)
                assertTrue("ops.role_forbidden" in support.bodyAsText())
            }
        }

    @Test
    fun `only an admin adds or removes a destination or withdraws`() =
        testApplication {
            application { harness.install(this) }
            val added = harness.feeWallets.addDestination(destination(), staff()) as Resource.Success
            val id = added.data.id

            for (role in listOf(StaffRole.FINANCE, StaffRole.SUPPORT)) {
                val add = write("/v1/ops/fee-wallets/destinations", destinationBody(), role)
                val withdraw = write("/v1/ops/fee-wallets/withdrawals", withdrawalBody(id.toString()), role)
                val remove = client.delete("/v1/ops/fee-wallets/destinations/$id") { signedIn(role) }
                for (response in listOf(add, withdraw, remove)) {
                    assertEquals(HttpStatusCode.Forbidden, response.status, "$role")
                    assertTrue("ops.role_forbidden" in response.bodyAsText())
                }
            }

            assertEquals(1, harness.feeWallets.destinations.size, "nothing was added or removed")
            assertTrue(harness.feeWallets.asked.isEmpty(), "and the wallet was never asked to send")
            assertTrue(harness.audit.entries.isEmpty(), "a refused role does nothing to audit")
        }

    private fun destination() = OpsNewFeeDestination(Network.MAINNET, "BASE", treasury, "Treasury")

    private fun staff() = OpsFeeStaff(UUID.randomUUID(), "someone@circaso.com")

    @Test
    fun `the wallets are one network's, with amounts as text in minor units`() =
        testApplication {
            application { harness.install(this) }

            val missing = read("/v1/ops/fee-wallets")
            val wrong = read("/v1/ops/fee-wallets?network=moon")
            val response = read("/v1/ops/fee-wallets?network=TESTNET")

            assertEquals(HttpStatusCode.BadRequest, missing.status)
            assertTrue("request.malformed" in missing.bodyAsText() && "network" in missing.bodyAsText())
            assertEquals(HttpStatusCode.BadRequest, wrong.status)
            assertEquals(listOf(Network.TESTNET), harness.feeWallets.walletReads.toList())
            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.OK, response.status, body)
            for (
            expected in
            listOf(
                "\"network\":\"TESTNET\"",
                "\"status\":\"READY\"",
                "\"destinationDelayHours\":24",
                "\"blockchain\":\"BASE-SEPOLIA\"",
                "\"address\":\"${FakeFeeWallets.FEE_ADDRESS}\"",
                "\"balances\":[{\"symbol\":\"USDC\",\"amountMinor\":\"$UINT128\",\"decimals\":6}]",
                "\"nativeBalance\":{\"symbol\":\"ETH\",\"amountMinor\":\"1000000000000000\",\"decimals\":18}",
                "\"nativeBalance\":null,\"balancesKnown\":false,\"withdrawable\":false",
                "\"unavailableReason\":\"no Circle wallet behind it\"",
            )
            ) {
                assertTrue(expected in body, "$expected in $body")
            }
        }

    @Test
    fun `adding a destination needs a reason and is audited with where it points and when it becomes usable`() =
        testApplication {
            application { harness.install(this) }

            val noReason = write("/v1/ops/fee-wallets/destinations", destinationBody(reason = " "))
            val noNetwork =
                write("/v1/ops/fee-wallets/destinations", destinationBody().replace("MAINNET", "MOON"))
            val added = write("/v1/ops/fee-wallets/destinations", destinationBody())

            assertEquals(HttpStatusCode.BadRequest, noReason.status)
            assertTrue("ops.reason_required" in noReason.bodyAsText())
            assertEquals(HttpStatusCode.BadRequest, noNetwork.status)
            assertTrue("request.malformed" in noNetwork.bodyAsText())
            val body = added.bodyAsText()
            assertEquals(HttpStatusCode.OK, added.status, body)
            val destination = harness.feeWallets.destinations.single()
            assertTrue("\"id\":\"${destination.id}\"" in body, body)
            assertTrue("\"createdBy\":\"admin@circaso.com\"" in body, body)
            assertTrue("\"createdAt\":\"2026-11-19T09:00:00Z\",\"usableAt\":\"2026-11-20T09:00:00Z\"" in body, body)

            val line = lines("fee_destination.add").single()
            assertEquals(AuditOutcome.SUCCEEDED, line.outcome)
            assertEquals("fee_destination", line.targetType)
            assertEquals("Company treasury, approved by the board", line.reason)
            assertEquals(harness.ids.getValue(StaffRole.ADMIN), line.actorId)
            assertEquals(
                "add MAINNET BASE $treasury (${destination.id}), usable 2026-11-20T09:00:00Z",
                line.detail,
            )
        }

    @Test
    fun `the destinations list says whether each can be used yet, by network`() =
        testApplication {
            application { harness.install(this) }
            write("/v1/ops/fee-wallets/destinations", destinationBody())

            val all = read("/v1/ops/fee-wallets/destinations").bodyAsText()
            val testnet = read("/v1/ops/fee-wallets/destinations?network=TESTNET").bodyAsText()

            assertTrue("\"label\":\"Treasury\"" in all && "\"network\":\"MAINNET\"" in all, all)
            assertTrue("\"usable\":" in all, all)
            assertEquals("""{"destinations":[]}""", testnet.substringAfter("\"data\":").removeSuffix("}"), testnet)
        }

    @Test
    fun `removing a destination is audited, and one that isn't there is not found`() =
        testApplication {
            application { harness.install(this) }
            write("/v1/ops/fee-wallets/destinations", destinationBody())
            val id =
                harness.feeWallets.destinations
                    .single()
                    .id

            val removed =
                client.delete("/v1/ops/fee-wallets/destinations/$id?reason=No%20longer%20ours") {
                    signedIn(StaffRole.ADMIN)
                }
            val again = client.delete("/v1/ops/fee-wallets/destinations/$id") { signedIn(StaffRole.ADMIN, "key-2") }
            val notAnId = client.delete("/v1/ops/fee-wallets/destinations/nope") { signedIn(StaffRole.ADMIN, "key-3") }

            assertEquals(HttpStatusCode.OK, removed.status, removed.bodyAsText())
            assertTrue(harness.feeWallets.destinations.isEmpty())
            assertEquals(HttpStatusCode.NotFound, again.status)
            assertTrue("fees.destination_not_found" in again.bodyAsText())
            assertEquals(HttpStatusCode.BadRequest, notAnId.status)
            val lines = lines("fee_destination.remove")
            assertEquals(listOf(AuditOutcome.SUCCEEDED, AuditOutcome.FAILED), lines.map { it.outcome })
            assertEquals(id.toString(), lines.first().targetId)
            assertEquals("No longer ours", lines.first().reason)
            assertEquals("remove MAINNET BASE $treasury", lines.first().detail)
            assertEquals("fees.destination_not_found", lines.last().errorCode)
        }

    @Test
    fun `a withdrawal is audited with who, why, the network, the chain, the amount and the destination`() =
        testApplication {
            application { harness.install(this) }
            write("/v1/ops/fee-wallets/destinations", destinationBody())
            val destination =
                harness.feeWallets.destinations
                    .single()
                    .id

            val response = write(WITHDRAWALS, withdrawalBody(destination.toString()), key = "sweep-1")

            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.OK, response.status, body)
            val asked = harness.feeWallets.asked.single()
            assertEquals(BigInteger("2500000"), asked.amountMinor)
            assertEquals("sweep-1", asked.idempotencyKey, "the request's Idempotency-Key reaches the wallet")
            assertEquals(Network.MAINNET to "Monthly sweep", asked.network to asked.reason)
            val made = harness.feeWallets.withdrawals.single()
            assertTrue("\"amountMinor\":\"2500000\",\"decimals\":6" in body, body)
            assertTrue(
                "\"destination\":{\"id\":\"$destination\",\"address\":\"$treasury\",\"label\":\"Treasury\"}" in body,
                body,
            )
            assertTrue("\"state\":\"SENT\"" in body && "\"by\":\"admin@circaso.com\"" in body, body)

            val line = lines("fee_withdrawal.create").single()
            assertEquals(AuditOutcome.SUCCEEDED, line.outcome)
            assertEquals("fee_withdrawal", line.targetType)
            assertEquals("admin@circaso.com" to StaffRole.ADMIN, line.actorEmail to line.actorRole)
            assertEquals("Monthly sweep", line.reason)
            assertEquals(
                "MAINNET BASE 2500000 USDC (minor units, 6 decimals) to $treasury (Treasury); " +
                    "withdrawal ${made.id} SENT",
                line.detail,
            )
        }

    @Test
    fun `an amount is whole minor units as text - a decimal, a number or a sign is refused before the wallet`() =
        testApplication {
            application { harness.install(this) }
            val destination = Uuid7.generate().toString()

            val notAmounts =
                listOf("2.5", "-1", "0", "1e6", "", " ", "0x10", "9".repeat(79)).map { "\"$it\"" } + "2.5"
            for (amount in notAmounts) {
                val response = write("/v1/ops/fee-wallets/withdrawals", withdrawalBody(destination, amount))
                assertEquals(HttpStatusCode.BadRequest, response.status, "$amount: ${response.bodyAsText()}")
            }
            val noReason = write("/v1/ops/fee-wallets/withdrawals", withdrawalBody(destination, reason = ""))
            val badDestination = write("/v1/ops/fee-wallets/withdrawals", withdrawalBody("not-an-id"))
            val badNetwork = write("/v1/ops/fee-wallets/withdrawals", withdrawalBody(destination, network = "mainnet2"))
            val noKey = write("/v1/ops/fee-wallets/withdrawals", withdrawalBody(destination), key = null)

            assertTrue("ops.reason_required" in noReason.bodyAsText())
            assertEquals(HttpStatusCode.BadRequest, badDestination.status)
            assertEquals(HttpStatusCode.BadRequest, badNetwork.status)
            assertEquals(HttpStatusCode.BadRequest, noKey.status)
            assertTrue("Idempotency-Key" in noKey.bodyAsText())
            assertTrue(harness.feeWallets.asked.isEmpty(), "the wallet was never asked")
            assertTrue(lines("fee_withdrawal.create").isEmpty(), "and nothing was audited as attempted")
        }

    @Test
    fun `what the wallet refuses reaches staff with its code, and the audit line fails with it`() =
        testApplication {
            application { harness.install(this) }
            val destination = Uuid7.generate()

            for (code in listOf("fees.destination_not_ready", "fees.insufficient_balance", "fees.insufficient_gas")) {
                harness.feeWallets.refusal = code
                val response = write(WITHDRAWALS, withdrawalBody(destination.toString()), key = code)

                assertEquals(HttpStatusCode.Conflict, response.status, code)
                assertTrue(code in response.bodyAsText(), response.bodyAsText())
            }

            val lines = lines("fee_withdrawal.create")
            assertEquals(List(3) { AuditOutcome.FAILED }, lines.map { it.outcome })
            assertEquals(
                listOf("fees.destination_not_ready", "fees.insufficient_balance", "fees.insufficient_gas"),
                lines.map { it.errorCode },
            )
            assertTrue(
                lines.all {
                    it.detail == "MAINNET BASE 2500000 USDC (minor units) to destination $destination" &&
                        it.reason == "Monthly sweep" &&
                        it.targetId == destination.toString()
                },
                "what was asked is on the line even though nothing was sent: ${lines.map { it.detail }}",
            )
            assertFalse(harness.feeWallets.withdrawals.any())
            assertNull(harness.feeWallets.destination(destination))
        }

    @Test
    fun `the withdrawals list passes the network and the cursor to the wallet`() =
        testApplication {
            application { harness.install(this) }
            write("/v1/ops/fee-wallets/destinations", destinationBody())
            val destination =
                harness.feeWallets.destinations
                    .single()
                    .id
            write("/v1/ops/fee-wallets/withdrawals", withdrawalBody(destination.toString()))

            val mainnet = read("/v1/ops/fee-wallets/withdrawals?network=MAINNET").bodyAsText()
            val testnet = read("/v1/ops/fee-wallets/withdrawals?network=TESTNET").bodyAsText()
            val badLimit = read("/v1/ops/fee-wallets/withdrawals?limit=0")

            assertTrue("\"items\":[{\"id\":\"" in mainnet && "\"nextCursor\":null" in mainnet, mainnet)
            assertTrue("\"items\":[]" in testnet, testnet)
            assertEquals(HttpStatusCode.BadRequest, badLimit.status)
        }
}
