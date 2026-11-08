package com.circaso.ops

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.OpsGroup
import com.circaso.ops.presentation.dto.TransferDto
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import com.circaso.wallet.api.OpsTransfer
import com.circaso.wallet.api.OpsTransferKind
import com.circaso.wallet.api.TransferId
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.openapi.JsonSchema
import io.ktor.openapi.KotlinxJsonSchemaInference
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigInteger
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Both sides of a transfer as typed parties: a person, a group's wallet as that group (never "not a user"), an outside
 * address, and Circaso's fee wallet as Circaso's fee, decided by the wallet's own record, never a name. One group
 * lookup per page.
 */
class TransferPartiesTest {
    private val harness = OpsHarness()
    private val jane = harness.accounts.add()
    private val bob = harness.accounts.add()
    private val lunch = ChatId(Uuid7.generate())
    private val groupWallet = UserId.new()

    init {
        harness.profiles.name(jane, "Jane")
        harness.profiles.name(bob, "Bob")
        harness.groups.groups[lunch] = OpsGroup(lunch, 6, jane, EPOCH)
    }

    private fun transfer(
        sender: UserId,
        recipient: UserId?,
        destination: String = "0x" + "a".repeat(40),
        fromGroup: ChatId? = null,
        toGroup: ChatId? = null,
        fee: Long = 0,
        toFeeWallet: Boolean = false,
    ) = OpsTransfer(
        transferId = TransferId.new(),
        senderId = sender,
        recipientId = recipient,
        destinationAddress = destination,
        blockchain = "BASE-SEPOLIA",
        asset = "USDC",
        amountMinor = BigInteger.valueOf(100_000_000),
        decimals = 6,
        feeMinor = BigInteger.valueOf(fee),
        usdTotalMicros = 100_000_000 + fee,
        state = "CONFIRMED",
        origin = "APP",
        kind = OpsTransferKind.PERSON,
        txHash = null,
        failureReason = null,
        createdAt = EPOCH,
        updatedAt = EPOCH,
        fromGroupId = fromGroup,
        toGroupId = toGroup,
        feeAddress = if (fee > 0) FEE_WALLET else null,
        toFeeWallet = toFeeWallet,
    )

    @Test
    fun `people, groups, outside addresses and Circaso's fee each read as what they are`() =
        testApplication {
            application { harness.install(this) }
            harness.wallets.transfers +=
                listOf(
                    transfer(jane, groupWallet, toGroup = lunch, fee = 1_000_000),
                    transfer(groupWallet, bob, fromGroup = lunch),
                    transfer(jane, null, destination = "0x1234567890abcdef1234567890abcdef12345678"),
                    transfer(bob, null, destination = FEE_WALLET, toFeeWallet = true),
                )

            val body =
                client
                    .get("/v1/ops/transactions") { header(OpsHarness.ROLE_HEADER, "FINANCE") }
                    .bodyAsText()
            val items =
                Json
                    .parseToJsonElement(body)
                    .jsonObject["data"]!!
                    .jsonObject["items"]!!
                    .jsonArray
                    .map { it.jsonObject }

            val (toGroup, fromGroup, out, toFee) = items
            assertEquals(party("USER", jane.toString(), "Jane"), toGroup.side("sender"))
            assertEquals(party("GROUP", lunch.value.toString(), null, 6), toGroup.side("recipient"))
            assertEquals("null", toGroup["recipientName"].toString(), "never a group wallet's id read as a person")
            assertEquals(party("FEE", FEE_WALLET, "Circaso fee"), toGroup["fee"]!!.jsonObject.side("to"))
            assertEquals("1000000", toGroup["fee"]!!.jsonObject["amountMinor"]!!.jsonPrimitive.content)
            assertEquals(party("GROUP", lunch.value.toString(), null, 6), fromGroup.side("sender"))
            assertEquals(party("USER", bob.toString(), "Bob"), fromGroup.side("recipient"))
            assertEquals("null", fromGroup["fee"].toString())
            assertEquals(
                party("EXTERNAL", "0x1234567890abcdef1234567890abcdef12345678", "0x1234…5678"),
                out.side("recipient"),
            )
            assertEquals(party("FEE", FEE_WALLET, "Circaso fee"), toFee.side("recipient"))
            assertEquals(listOf(setOf(lunch)), harness.groups.lookups, "one batched group lookup for the page")
        }

    @Test
    fun `a group that is gone still reads as a group, without a member count`() =
        testApplication {
            application { harness.install(this) }
            val gone = ChatId(Uuid7.generate())
            val sent = transfer(jane, groupWallet, toGroup = gone)
            harness.wallets.transfers += sent

            val body =
                client
                    .get("/v1/ops/transactions/${sent.transferId}") { header(OpsHarness.ROLE_HEADER, "ADMIN") }
                    .bodyAsText()
            val data = Json.parseToJsonElement(body).jsonObject["data"]!!.jsonObject

            assertEquals(party("GROUP", gone.value.toString(), null), data.side("recipient"))
        }

    @Test
    fun `the old name fields are marked deprecated in the OpenAPI schema`() {
        val schema =
            Json.encodeToString(JsonSchema.serializer(), KotlinxJsonSchemaInference.buildSchema(typeOf<TransferDto>()))
        val sender = Regex("\"senderName\":\\{[^}]*\\}").find(schema)?.value.orEmpty()
        val recipient = Regex("\"recipientName\":\\{[^}]*\\}").find(schema)?.value.orEmpty()

        assertTrue(sender.contains("\"deprecated\":true"), schema)
        assertTrue(recipient.contains("\"deprecated\":true"), schema)
    }

    private fun JsonObject.side(name: String): Map<String, String?> =
        this[name]!!
            .jsonObject
            .filterValues { it.toString() != "null" }
            .mapValues { (_, value) -> value.jsonPrimitive.content }

    private fun party(
        kind: String,
        id: String,
        name: String?,
        memberCount: Int? = null,
    ): Map<String, String?> =
        buildMap {
            put("kind", kind)
            put("id", id)
            name?.let { put("name", it) }
            memberCount?.let { put("memberCount", it.toString()) }
        }

    private companion object {
        const val FEE_WALLET = "0xfeefeefeefeefeefeefeefeefeefeefeefeefee0"
    }
}
