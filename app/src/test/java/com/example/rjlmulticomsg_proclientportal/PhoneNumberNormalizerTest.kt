package com.example.rjlmulticomsg_proclientportal

import com.example.rjlmulticomsg_proclientportal.domain.phone.PhoneNumberNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumberNormalizerTest {

    private fun ok(raw: String): String {
        val r = PhoneNumberNormalizer.normalize(raw)
        assertTrue("Expected valid for '$raw', got $r", r is PhoneNumberNormalizer.Result.Valid)
        return (r as PhoneNumberNormalizer.Result.Valid).e164
    }

    private fun bad(raw: String?) {
        val r = PhoneNumberNormalizer.normalize(raw)
        assertTrue("Expected invalid for '$raw', got $r", r is PhoneNumberNormalizer.Result.Invalid)
    }

    @Test
    fun australianMobileVariantsNormaliseToE164() {
        val expected = "+61412345678"
        assertEquals(expected, ok("0412 345 678"))
        assertEquals(expected, ok("0412345678"))
        assertEquals(expected, ok("61412345678"))
        assertEquals(expected, ok("+61 412 345 678"))
        assertEquals(expected, ok("+61412345678"))
        assertEquals(expected, ok("0412-345-678"))
        assertEquals(expected, ok("(0412) 345 678"))
    }

    @Test
    fun internationalE164Preserved() {
        assertEquals("+14155552671", ok("+1 415 555 2671"))
        assertEquals("+442071838750", ok("+44 20 7183 8750"))
    }

    @Test
    fun rejectsEmptyPrivateWithheldMalformed() {
        bad(null)
        bad("")
        bad("   ")
        bad("private")
        bad("WITHHELD")
        bad("Anonymous")
        bad("unknown")
        bad("123")
        bad("++++")
        bad("not-a-number")
    }

    @Test
    fun isWithheldOrPrivate() {
        assertTrue(PhoneNumberNormalizer.isWithheldOrPrivate("WITHHELD"))
        assertTrue(PhoneNumberNormalizer.isWithheldOrPrivate(null))
        assertFalse(PhoneNumberNormalizer.isWithheldOrPrivate("+61412345678"))
    }

    @Test
    fun maskForLogDoesNotExposeFullNumber() {
        val masked = PhoneNumberNormalizer.maskForLog("+61412345678")
        assertFalse(masked.contains("412345678"))
        assertTrue(masked.endsWith("678") || masked.contains("•"))
    }

    @Test
    fun formatDisplayFriendlyAu() {
        val display = PhoneNumberNormalizer.formatDisplay("+61412345678")
        assertTrue(display.contains("412"))
        assertTrue(display.startsWith("+61"))
    }
}
