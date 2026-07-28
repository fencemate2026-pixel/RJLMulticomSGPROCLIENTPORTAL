package com.example.rjlmulticomsg_proclientportal.security

import android.content.Context
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

enum class AppLockState {
    WAITING_FOR_SESSION,
    NEEDS_SETUP,
    LOCKED,
    UNLOCKED
}

enum class PinVerifyResult {
    SUCCESS,
    INCORRECT,
    REQUIRE_LOGIN
}

/**
 * Device-local app lock. The PIN is never stored: only a HMAC produced by a
 * non-exportable Android Keystore key is persisted.
 */
class AppLockManager(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutableState = mutableStateOf(AppLockState.WAITING_FOR_SESSION)
    val state: State<AppLockState> = mutableState

    private var activeUserId: String? = null
    private var backgroundElapsedRealtime: Long? = null
    private var initialisedForProcess = false

    val failedAttempts: Int
        get() = preferences.getInt(KEY_FAILED_ATTEMPTS, 0)

    fun onSessionAvailable(userId: String) {
        activeUserId = userId
        val storedUser = preferences.getString(KEY_USER_ID, null)
        val hasPin = preferences.contains(KEY_VERIFIER) && storedUser == userId

        if (!initialisedForProcess) {
            mutableState.value = if (hasPin) AppLockState.LOCKED else AppLockState.NEEDS_SETUP
            initialisedForProcess = true
        } else if (!hasPin) {
            mutableState.value = AppLockState.NEEDS_SETUP
        }
    }

    fun onSignedOut(clearPin: Boolean = true) {
        activeUserId = null
        backgroundElapsedRealtime = null
        if (clearPin) clearStoredPin()
        mutableState.value = AppLockState.WAITING_FOR_SESSION
    }

    fun setupPin(pin: String): Boolean {
        val userId = activeUserId ?: return false
        if (!isValidPin(pin)) return false
        val verifier = hmac(pin, userId)
        preferences.edit()
            .putString(KEY_USER_ID, userId)
            .putString(KEY_VERIFIER, verifier.toHex())
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .apply()
        mutableState.value = AppLockState.UNLOCKED
        return true
    }

    fun verifyPin(pin: String): PinVerifyResult {
        val userId = activeUserId ?: return PinVerifyResult.REQUIRE_LOGIN
        val expected = preferences.getString(KEY_VERIFIER, null)
            ?.hexToBytes()
            ?: return PinVerifyResult.REQUIRE_LOGIN
        val actual = hmac(pin, userId)
        if (MessageDigest.isEqual(expected, actual)) {
            preferences.edit().putInt(KEY_FAILED_ATTEMPTS, 0).apply()
            mutableState.value = AppLockState.UNLOCKED
            return PinVerifyResult.SUCCESS
        }

        val attempts = failedAttempts + 1
        preferences.edit().putInt(KEY_FAILED_ATTEMPTS, attempts).apply()
        if (attempts >= MAX_ATTEMPTS) {
            clearStoredPin()
            mutableState.value = AppLockState.WAITING_FOR_SESSION
            return PinVerifyResult.REQUIRE_LOGIN
        }
        return PinVerifyResult.INCORRECT
    }

    fun lockNow() {
        if (activeUserId != null && preferences.contains(KEY_VERIFIER)) {
            mutableState.value = AppLockState.LOCKED
        }
    }

    fun onBackgrounded() {
        if (mutableState.value == AppLockState.UNLOCKED) {
            backgroundElapsedRealtime = SystemClock.elapsedRealtime()
        }
    }

    fun onForegrounded() {
        val backgroundAt = backgroundElapsedRealtime ?: return
        backgroundElapsedRealtime = null
        if (SystemClock.elapsedRealtime() - backgroundAt >= BACKGROUND_LOCK_MS) {
            lockNow()
        }
    }

    fun forgetPin() {
        clearStoredPin()
        mutableState.value = AppLockState.WAITING_FOR_SESSION
    }

    private fun clearStoredPin() {
        preferences.edit()
            .remove(KEY_USER_ID)
            .remove(KEY_VERIFIER)
            .remove(KEY_FAILED_ATTEMPTS)
            .apply()
    }

    private fun hmac(pin: String, userId: String): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(getOrCreateKey())
        return mac.doFinal("$userId:$pin".toByteArray(Charsets.UTF_8))
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
            KEYSTORE
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        return generator.generateKey()
    }

    private fun isValidPin(pin: String): Boolean =
        pin.length == PIN_LENGTH && pin.all(Char::isDigit)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray? {
        if (length % 2 != 0) return null
        return runCatching {
            ByteArray(length / 2) { index ->
                substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }.getOrNull()
    }

    companion object {
        const val PIN_LENGTH = 4
        const val MAX_ATTEMPTS = 5
        const val BACKGROUND_LOCK_MS = 5 * 60_000L

        private const val PREFS = "sgpro_app_lock"
        private const val KEY_USER_ID = "pin_user_id"
        private const val KEY_VERIFIER = "pin_verifier"
        private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        private const val KEY_ALIAS = "sgpro_pin_hmac_v1"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val HMAC_ALGORITHM = "HmacSHA256"
    }
}
