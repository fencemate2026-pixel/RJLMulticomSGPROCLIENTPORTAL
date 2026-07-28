package com.example.rjlmulticomsg_proclientportal.domain.model

/**
 * Roles allowed on GSM whitelist entries (broader than portal UserRole).
 */
enum class GsmCallerRole {
    OWNER,
    MEMBER,
    STAFF,
    TEMPORARY;

    companion object {
        fun from(raw: String?): GsmCallerRole =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: MEMBER
    }
}

enum class GsmCallerStatus {
    ACTIVE,
    DISABLED,
    NOT_STARTED,
    EXPIRED,
    INVALID_NUMBER
}

data class GsmCaller(
    /** Firestore / Room document id */
    val id: String,
    val accountId: String,
    val displayName: String,
    /** Canonical E.164 storage */
    val phoneNumberE164: String,
    val enabled: Boolean = true,
    val validFrom: Long? = null,
    val validUntil: Long? = null,
    val role: GsmCallerRole = GsmCallerRole.MEMBER,
    val notes: String = "",
    /** Optional link to clientUsers/{uid} for membership / open-gate checks */
    val linkedUserId: String? = null,
    val createdBy: String = "",
    val createdAt: Long = 0L,
    val updatedBy: String = "",
    val updatedAt: Long = 0L,
    val localUpdatedAt: Long = System.currentTimeMillis(),
    val serverUpdatedAt: Long = 0L,
    val pendingSync: Boolean = false
) {
    val displayPhone: String
        get() = com.example.rjlmulticomsg_proclientportal.domain.phone.PhoneNumberNormalizer
            .formatDisplay(phoneNumberE164)

    fun status(nowMs: Long = System.currentTimeMillis()): GsmCallerStatus {
        val numberOk = com.example.rjlmulticomsg_proclientportal.domain.phone.PhoneNumberNormalizer
            .validateE164(phoneNumberE164) is
            com.example.rjlmulticomsg_proclientportal.domain.phone.PhoneNumberNormalizer.Result.Valid
        if (!numberOk) return GsmCallerStatus.INVALID_NUMBER
        if (!enabled) return GsmCallerStatus.DISABLED
        if (validFrom != null && nowMs < validFrom) return GsmCallerStatus.NOT_STARTED
        if (validUntil != null && nowMs > validUntil) return GsmCallerStatus.EXPIRED
        return GsmCallerStatus.ACTIVE
    }

    fun isAuthorisedNow(nowMs: Long = System.currentTimeMillis()): Boolean =
        status(nowMs) == GsmCallerStatus.ACTIVE
}

data class GsmDeviceStatus(
    val deviceId: String,
    val accountId: String,
    val deviceName: String = "",
    val enabled: Boolean = true,
    val firmwareVersion: String = "",
    val whitelistVersion: Long = 0L,
    val lastSeenAt: Long = 0L,
    val signalStrength: Int? = null,
    val networkRegistered: Boolean = false,
    val modemModel: String = "",
    val operator: String = "",
    val radioTechnology: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val gnssAltitudeMetres: Double? = null,
    val gnssSpeedKnots: Double? = null,
    val gnssHeadingDegrees: Double? = null,
    val gnssCapturedAt: Long = 0L,
    val gnssSource: String = "",
    val lastSyncAt: Long = 0L,
    val lastError: String? = null,
    val gsmDeviceTimeMs: Long = 0L,
    val localUpdatedAt: Long = System.currentTimeMillis()
) {
    fun isOffline(nowMs: Long = System.currentTimeMillis(), thresholdMs: Long = 15 * 60_000L): Boolean {
        if (lastSeenAt <= 0L) return true
        return nowMs - lastSeenAt > thresholdMs
    }

    val hasLocation: Boolean
        get() = latitude != null && longitude != null &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0
}

data class GsmCallLog(
    val id: String,
    val accountId: String,
    val deviceId: String,
    val callerNumberE164: String,
    val matchedCallerId: String? = null,
    val matchedCallerName: String? = null,
    val authorised: Boolean = false,
    val relayTriggered: Boolean = false,
    val rejectionReason: String = "",
    val receivedAt: Long = 0L,
    val uploadedAt: Long = 0L,
    val signalStrength: Int? = null
)

data class GsmWhitelistMeta(
    val accountId: String,
    val version: Long = 0L,
    val lastLocalSyncAt: Long = 0L,
    val lastServerSyncAt: Long = 0L,
    val pendingRefresh: Boolean = false
)

/** Human-readable status chip labels for UI. */
fun GsmCallerStatus.label(): String = when (this) {
    GsmCallerStatus.ACTIVE -> "Active"
    GsmCallerStatus.DISABLED -> "Disabled"
    GsmCallerStatus.NOT_STARTED -> "Not started"
    GsmCallerStatus.EXPIRED -> "Expired"
    GsmCallerStatus.INVALID_NUMBER -> "Invalid number"
}

object ActionTypes {
    const val GSM_OPEN_REQUESTED = "GSM_OPEN_REQUESTED"
    const val GSM_CALL_LAUNCHED = "GSM_CALL_LAUNCHED"
    const val GSM_CALL_LAUNCH_FAILED = "GSM_CALL_LAUNCH_FAILED"
    const val CALLER_ADDED = "CALLER_ADDED"
    const val CALLER_CHANGED = "CALLER_CHANGED"
    const val CALLER_ENABLED = "CALLER_ENABLED"
    const val CALLER_DISABLED = "CALLER_DISABLED"
    const val CALLER_DELETED = "CALLER_DELETED"
    const val WHITELIST_VERSION_CHANGED = "WHITELIST_VERSION_CHANGED"
    const val ESP32_WHITELIST_DOWNLOADED = "ESP32_WHITELIST_DOWNLOADED"
    const val ESP32_HEARTBEAT_RECEIVED = "ESP32_HEARTBEAT_RECEIVED"
    const val AUTHORISED_INCOMING_CALL = "AUTHORISED_INCOMING_CALL"
    const val REJECTED_INCOMING_CALL = "REJECTED_INCOMING_CALL"
    const val RELAY_TRIGGERED = "RELAY_TRIGGERED"
    const val RELAY_FAILED = "RELAY_FAILED"
    const val DEVICE_OFFLINE = "DEVICE_OFFLINE"
    const val DEVICE_CREDENTIAL_ROTATED = "DEVICE_CREDENTIAL_ROTATED"
    const val WHITELIST_REFRESH_REQUESTED = "WHITELIST_REFRESH_REQUESTED"
    const val OPEN_WIFI = "OPEN_WIFI"
    const val LOGIN = "LOGIN"
    const val LOGOUT = "LOGOUT"
}
