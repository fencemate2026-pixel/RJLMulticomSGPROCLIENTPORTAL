package com.example.rjlmulticomsg_proclientportal

import com.example.rjlmulticomsg_proclientportal.ui.magickey.magicKeyErrorMessage
import org.junit.Assert.assertTrue
import org.junit.Test

class MagicKeyErrorMessageTest {
    @Test fun mapsKnownBackendErrors() {
        assertTrue(magicKeyErrorMessage("expired", 403).contains("expired"))
        assertTrue(magicKeyErrorMessage("revoked", 403).contains("revoked"))
        assertTrue(magicKeyErrorMessage("insufficient_scope", 403).contains("not authorised"))
        assertTrue(magicKeyErrorMessage(null, 429).contains("Too many"))
    }
}
