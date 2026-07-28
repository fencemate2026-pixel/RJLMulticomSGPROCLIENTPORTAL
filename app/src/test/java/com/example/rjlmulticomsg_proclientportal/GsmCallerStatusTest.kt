package com.example.rjlmulticomsg_proclientportal

import com.example.rjlmulticomsg_proclientportal.domain.model.GsmCaller
import com.example.rjlmulticomsg_proclientportal.domain.model.GsmCallerRole
import com.example.rjlmulticomsg_proclientportal.domain.model.GsmCallerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GsmCallerStatusTest {

    private fun caller(
        enabled: Boolean = true,
        validFrom: Long? = null,
        validUntil: Long? = null,
        phone: String = "+61412345678"
    ) = GsmCaller(
        id = "c1",
        accountId = "acct_a",
        displayName = "Test",
        phoneNumberE164 = phone,
        enabled = enabled,
        validFrom = validFrom,
        validUntil = validUntil,
        role = GsmCallerRole.MEMBER
    )

    @Test
    fun activeWhenEnabledAndInWindow() {
        val now = 1_700_000_000_000L
        val c = caller(validFrom = now - 1000, validUntil = now + 1000)
        assertEquals(GsmCallerStatus.ACTIVE, c.status(now))
        assertTrue(c.isAuthorisedNow(now))
    }

    @Test
    fun disabled() {
        val c = caller(enabled = false)
        assertEquals(GsmCallerStatus.DISABLED, c.status())
        assertFalse(c.isAuthorisedNow())
    }

    @Test
    fun notStarted() {
        val now = 1_700_000_000_000L
        val c = caller(validFrom = now + 60_000)
        assertEquals(GsmCallerStatus.NOT_STARTED, c.status(now))
        assertFalse(c.isAuthorisedNow(now))
    }

    @Test
    fun expired() {
        val now = 1_700_000_000_000L
        val c = caller(validUntil = now - 1)
        assertEquals(GsmCallerStatus.EXPIRED, c.status(now))
        assertFalse(c.isAuthorisedNow(now))
    }

    @Test
    fun invalidNumber() {
        val c = caller(phone = "not-valid")
        assertEquals(GsmCallerStatus.INVALID_NUMBER, c.status())
        assertFalse(c.isAuthorisedNow())
    }
}
