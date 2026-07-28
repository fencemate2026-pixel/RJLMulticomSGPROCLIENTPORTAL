package com.example.rjlmulticomsg_proclientportal.ui.magickey

import org.junit.Assert.assertEquals
import org.junit.Test

class MagicKeyErrorMessageTest {

    @Test
    fun mapsKnownBackendErrors() {
        assertEquals(
            "That Magic Key is incorrect or has expired.",
            magicKeyErrorMessage("invalid_or_expired_key", 401)
        )

        assertEquals(
            "Enter the six-digit Magic Key.",
            magicKeyErrorMessage("key_must_be_six_digits", 400)
        )

        assertEquals(
            "Too many incorrect attempts. Wait 15 minutes and try again.",
            magicKeyErrorMessage("too_many_attempts", 429)
        )

        assertEquals(
            "This Magic Keys account is inactive. Contact RJL Commercial.",
            magicKeyErrorMessage("account_inactive", 403)
        )

        assertEquals(
            "The secure verification service is temporarily unavailable.",
            magicKeyErrorMessage("verification_unavailable", 500)
        )
    }
}
