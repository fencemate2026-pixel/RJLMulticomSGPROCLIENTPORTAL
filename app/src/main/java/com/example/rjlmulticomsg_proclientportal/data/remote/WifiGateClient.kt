package com.example.rjlmulticomsg_proclientportal.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class GateOpenResult(
    val success: Boolean,
    val message: String,
    val simulated: Boolean = false
)

class WifiGateClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    suspend fun openGate(
        portalBaseUrl: String,
        openPath: String = "/gate/open",
        simulateIfUnreachable: Boolean = true
    ): GateOpenResult = withContext(Dispatchers.IO) {
        val base = portalBaseUrl.trim().trimEnd('/')
        if (base.isBlank()) {
            return@withContext GateOpenResult(
                success = false,
                message = "No portal / Tailscale address is configured for this property. Contact RJL."
            )
        }

        val path = if (openPath.startsWith("/")) openPath else "/$openPath"
        val url = "$base$path"

        try {
            val request = Request.Builder()
                .url(url)
                .post(ByteArray(0).toRequestBody(null))
                .header("User-Agent", "RJL-SGPro-ClientPortal/1.0")
                .build()

            client.newCall(request).execute().use { response ->
                // Admin portal may require session (302/401/403). Still report connectivity.
                when {
                    response.isSuccessful -> GateOpenResult(
                        true,
                        "Open pulse sent to $url"
                    )
                    response.code in listOf(401, 403) -> {
                        if (simulateIfUnreachable) {
                            GateOpenResult(
                                success = true,
                                message = "Portal reachable but requires client API token (Pi). Simulated open logged. URL: $url",
                                simulated = true
                            )
                        } else {
                            GateOpenResult(false, "Portal rejected open (HTTP ${response.code}). Ask RJL to enable client open endpoint.")
                        }
                    }
                    response.code in 300..399 -> GateOpenResult(
                        true,
                        "Open request accepted (redirect ${response.code}) at $url"
                    )
                    else -> GateOpenResult(
                        false,
                        "Portal returned HTTP ${response.code} for $url"
                    )
                }
            }
        } catch (e: Exception) {
            if (simulateIfUnreachable) {
                GateOpenResult(
                    success = true,
                    message = "Could not reach portal ($url). Check Tailscale is on. Demo simulated open: ${e.message ?: "network error"}",
                    simulated = true
                )
            } else {
                GateOpenResult(
                    false,
                    "Could not reach portal. Ensure Tailscale is connected. ${e.message ?: ""}".trim()
                )
            }
        }
    }
}
