package com.circaso.shared

import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoneyAndPhoneNumberTest {
    @Test
    fun `money adds and subtracts in minor units`() {
        val a = Money(1_500_000, Asset.USDC)
        val b = Money(250_000, Asset.USDC)

        assertEquals(Money(1_750_000, Asset.USDC), a + b)
        assertEquals(Money(1_250_000, Asset.USDC), a - b)
        assertTrue(a > b)
        assertTrue(Money.zero(Asset.USDC).isZero)
        assertTrue((b - a).isNegative)
    }

    @Test
    fun `money of two assets never combines`() {
        assertFailsWith<IllegalArgumentException> { Money(1, Asset.USDC) + Money(1, Asset.USDT) }
        assertFailsWith<IllegalArgumentException> { Money(1, Asset.USDC).compareTo(Money(1, Asset.USDT)) }
    }

    @Test
    fun `money overflow fails loudly instead of wrapping`() {
        assertFailsWith<ArithmeticException> { Money(Long.MAX_VALUE, Asset.USDC) + Money(1, Asset.USDC) }
    }

    @Test
    fun `money never prints its amount`() {
        assertFalse(Money(123_456, Asset.USDC).toString().contains("123"))
    }

    @Test
    fun `phone numbers parse only as strict E164`() {
        assertNotNull(PhoneNumber.parse("+254712345678"))
        assertNotNull(PhoneNumber.parse(" +254712345678 "))
        assertNull(PhoneNumber.parse("0712345678"))
        assertNull(PhoneNumber.parse("+254 712 345 678"))
        assertNull(PhoneNumber.parse("+0712345678"))
        assertNull(PhoneNumber.parse("+12345"))
        assertNull(PhoneNumber.parse("+2547123456789012"))
        assertNull(PhoneNumber.parse(""))
    }

    @Test
    fun `phone numbers are masked in every string form`() {
        val phone = PhoneNumber.parse("+254712345678")!!

        assertEquals("+254 7•• ••• 678", phone.masked())
        assertEquals("+254 7•• ••• 678", phone.toString())
        assertEquals("+254712345678", phone.e164)
    }

    @Test
    fun `masking finds the country code of Egypt and South Africa too`() {
        assertEquals("+27 8•• ••• 789", PhoneNumber.parse("+27821234789")!!.masked())
        assertTrue(PhoneNumber.parse("+201001234567")!!.masked().startsWith("+20 1"))
    }

    @Test
    fun `a phone number serialises as its E164 string and rejects anything else`() {
        val phone = PhoneNumber.parse("+254712345678")!!
        val json = CircasoJson.encodeToString(PhoneNumber.serializer(), phone)

        assertEquals("\"+254712345678\"", json)
        assertEquals(phone, CircasoJson.decodeFromString(PhoneNumber.serializer(), json))
        assertFailsWith<SerializationException> {
            CircasoJson.decodeFromString(
                PhoneNumber.serializer(),
                "\"0712345678\"",
            )
        }
    }
}
