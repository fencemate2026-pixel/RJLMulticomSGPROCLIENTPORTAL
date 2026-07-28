package com.example.rjlmulticomsg_proclientportal.data.security

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Salted SHA-256 password storage for local (debug) accounts.
 * Uses [android.util.Base64] so it works on minSdk 24 (java.util.Base64 needs API 26).
 */
object PasswordHasher {
    private const val ITERATIONS_MARKER = "v1"
    private val random = SecureRandom()

    fun hash(password: String): String {
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val digest = sha256(salt + password.toByteArray(Charsets.UTF_8))
        val saltB64 = Base64.encodeToString(salt, Base64.NO_WRAP)
        val hashB64 = Base64.encodeToString(digest, Base64.NO_WRAP)
        return "$ITERATIONS_MARKER\$$saltB64\$$hashB64"
    }

    fun verify(password: String, stored: String): Boolean {
        val parts = stored.split("$")
        if (parts.size != 3 || parts[0] != ITERATIONS_MARKER) return false
        return try {
            val salt = Base64.decode(parts[1], Base64.NO_WRAP)
            val expected = Base64.decode(parts[2], Base64.NO_WRAP)
            val actual = sha256(salt + password.toByteArray(Charsets.UTF_8))
            MessageDigest.isEqual(expected, actual)
        } catch (_: Exception) {
            false
        }
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)
}
