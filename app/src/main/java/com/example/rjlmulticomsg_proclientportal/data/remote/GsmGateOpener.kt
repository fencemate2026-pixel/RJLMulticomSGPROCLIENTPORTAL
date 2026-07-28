package com.example.rjlmulticomsg_proclientportal.data.remote

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import com.example.rjlmulticomsg_proclientportal.domain.phone.PhoneNumberNormalizer

data class GsmOpenResult(
    val success: Boolean,
    val message: String,
    val usedDialerFallback: Boolean = false,
    /** Android only launched the dialler/call — not ESP32 relay confirmation. */
    val dialLaunched: Boolean = false
)

/**
 * Places a call to the property SIM7600 number.
 *
 * The ESP32/SIM7600 rejects the call and pulses the relay when the caller is
 * authorised. This app does **not** hang up programmatically and does not treat
 * dialler launch as a successful gate open.
 */
class GsmGateOpener {
    fun hasCallPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Launch the safest supported call/dial flow for [gsmNumberE164].
     */
    fun openGate(
        context: Context,
        gsmNumberE164: String
    ): GsmOpenResult {
        val number = when (val r = PhoneNumberNormalizer.normalize(gsmNumberE164)) {
            is PhoneNumberNormalizer.Result.Valid -> r.e164
            is PhoneNumberNormalizer.Result.Invalid ->
                return GsmOpenResult(
                    false,
                    "Property GSM number is invalid (${r.reason}). Contact RJL to update it."
                )
        }

        val uri = Uri.parse("tel:$number")
        val chargeNote =
            "The gate controller should reject the call immediately. " +
                "Carrier call charges may still depend on your mobile provider."

        if (!hasCallPermission(context)) {
            val dial = Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                context.startActivity(dial)
                GsmOpenResult(
                    success = true,
                    message = "Dialler opened for the site GSM number. $chargeNote",
                    usedDialerFallback = true,
                    dialLaunched = true
                )
            } catch (e: Exception) {
                GsmOpenResult(false, "Could not open dialler: ${e.message}")
            }
        }

        return try {
            val call = Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(call)
            GsmOpenResult(
                success = true,
                message = "Calling site GSM number. $chargeNote",
                dialLaunched = true
            )
        } catch (e: SecurityException) {
            val dial = Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(dial)
                GsmOpenResult(
                    success = true,
                    message = "Permission limited — dialler opened. $chargeNote",
                    usedDialerFallback = true,
                    dialLaunched = true
                )
            } catch (e2: Exception) {
                GsmOpenResult(false, "Call failed: ${e2.message}")
            }
        } catch (e: Exception) {
            GsmOpenResult(false, "Call failed: ${e.message}")
        }
    }
}
