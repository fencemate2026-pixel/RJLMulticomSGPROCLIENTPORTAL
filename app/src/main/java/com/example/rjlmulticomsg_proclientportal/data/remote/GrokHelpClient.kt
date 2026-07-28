package com.example.rjlmulticomsg_proclientportal.data.remote

import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await

/**
 * Secure SG-PRO help client. Provider credentials remain in Cloud Functions
 * secrets and are never compiled into the Android application.
 */
class GrokHelpClient {
    private val tag = "GrokHelpClient"
    private val functions: FirebaseFunctions? by lazy {
        runCatching {
            FirebaseFunctions.getInstance(
                FirebaseApp.getInstance(),
                "australia-southeast1"
            )
        }.getOrNull()
    }

    val isConfigured: Boolean
        get() = functions != null

    data class ChatMessage(
        val role: String,
        val content: String
    )

    suspend fun ask(
        userMessage: String,
        history: List<ChatMessage> = emptyList(),
        pageContext: String = ""
    ): Result<String> {
        val client = functions ?: return Result.success(offlineHelp(userMessage))
        return try {
            @Suppress("UNCHECKED_CAST")
            val data = client.getHttpsCallable("askPortalAssistant")
                .call(
                    mapOf(
                        "message" to userMessage.trim(),
                        "history" to history.takeLast(12).map {
                            mapOf("role" to it.role, "content" to it.content)
                        },
                        "pageContext" to pageContext.take(1_000)
                    )
                )
                .await()
                .data as? Map<String, Any?>
            val reply = data?.get("reply")?.toString()?.trim().orEmpty()
            if (reply.isBlank()) {
                Result.success(offlineHelp(userMessage))
            } else {
                Result.success(reply)
            }
        } catch (e: Exception) {
            Log.w(tag, "Cloud assistant unavailable; using offline help: ${e.message}")
            Result.success(offlineHelp(userMessage))
        }
    }

    companion object {
        fun offlineHelp(question: String): String {
            val q = question.lowercase()
            return when {
                "login" in q || "sign in" in q || "password" in q ->
                    "Sign in with the email and password RJL issued you. Use **Forgot password** for a reset email. After sign-in, create your private **4-digit PIN**."
                "pin" in q || "lock" in q ->
                    "Your **4-digit PIN** protects the app on this device. It is stored as a secure verifier, not readable digits. Use **Forgot PIN** to sign out and create a new one."
                "message" in q || "sms" in q ->
                    "Open **Messages**, choose one or more active callers, enter up to 160 characters, then confirm. The on-site SIM7600 sends each SMS from its SIM card."
                "location" in q || "gps" in q || "map" in q ->
                    "Open **Device location**. A SIM7600 GPS antenna and outdoor GNSS fix are required before the satellite map can show the controller."
                "gate" in q || "open" in q ->
                    "On **Home**, use the main Open Gate button. Wi-Fi opens through the site portal; GSM calls the property SIM, and the controller must authorise your caller ID."
                "gsm" in q || "caller" in q || "whitelist" in q ->
                    "Open **Authorised callers**. Owners can add, edit, disable, or remove numbers. The controller reports when it has applied the matching cloud whitelist version."
                "module" in q || "wifi" in q || "rfid" in q || "lpr" in q ->
                    "Open **Settings → Modules**, enable only the equipment installed at the property, then save."
                "people" in q || "family" in q || "member" in q ->
                    "Open **Portal users**. Owners can manage member logins; authorised GSM callers are managed separately."
                "schedule" in q ->
                    "Open **Schedules** to manage permitted days and times."
                "log" in q || "status" in q ->
                    "Use **Status** in the bottom bar to see recent portal and gate actions."
                else ->
                    "I can help with **login**, **PIN security**, **gate access**, **callers**, **SMS**, **device location**, schedules, status, and settings."
            }
        }
    }
}
