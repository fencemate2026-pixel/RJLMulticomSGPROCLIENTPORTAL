package com.example.rjlmulticomsg_proclientportal.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: String,
    val siteName: String,
    val portalBaseUrl: String,
    val gsmNumber: String,
    val wifiHost: String,
    val openPath: String,
    val address: String = "",
    val region: String = "",
    val notes: String = "",
    /** WIFI | GSM | HYBRID */
    val connectionType: String = "HYBRID",
    /** Client finished first-run setup (gate type + site details). */
    val onboardingComplete: Boolean = false,
    /**
     * Legacy: local UI only. Controller never opens for unlisted numbers.
     */
    val gsmAllowAnyCaller: Boolean = false,
    val enabled: Boolean = true,
    val timezone: String = "Australia/Melbourne",
    val whitelistVersion: Long = 0L,
    val createdAt: Long
)

@Entity(
    tableName = "users",
    indices = [Index(value = ["email"], unique = true)]
)
data class UserEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val email: String,
    val passwordHash: String,
    val displayName: String,
    val role: String,
    val enabled: Boolean,
    val mustChangePassword: Boolean,
    val modulesJson: String = "[]"
)

@Entity(
    tableName = "user_sites",
    primaryKeys = ["userId", "accountId"],
    indices = [Index(value = ["userId"]), Index(value = ["accountId"])]
)
data class UserSiteEntity(
    val userId: String,
    val accountId: String
)

@Entity(
    tableName = "account_modules",
    primaryKeys = ["accountId", "moduleType"]
)
data class AccountModuleEntity(
    val accountId: String,
    val moduleType: String,
    val enabled: Boolean
)

/**
 * Authorised GSM callers (whitelist).
 * [id] is the Firestore document id (or a local UUID pending sync).
 */
@Entity(
    tableName = "gsm_callers",
    indices = [
        Index(value = ["accountId"]),
        Index(value = ["accountId", "phoneNumberE164"], unique = true)
    ]
)
data class GsmCallerEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val displayName: String,
    val phoneNumberE164: String,
    val enabled: Boolean = true,
    val validFrom: Long? = null,
    val validUntil: Long? = null,
    val role: String = "MEMBER",
    val notes: String = "",
    val linkedUserId: String? = null,
    val createdBy: String = "",
    val createdAt: Long = 0L,
    val updatedBy: String = "",
    val updatedAt: Long = 0L,
    val localUpdatedAt: Long = System.currentTimeMillis(),
    val serverUpdatedAt: Long = 0L,
    val pendingSync: Boolean = false
)

@Entity(tableName = "gsm_devices")
data class GsmDeviceEntity(
    @PrimaryKey val deviceId: String,
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
)

@Entity(
    tableName = "gsm_call_logs",
    indices = [Index(value = ["accountId", "receivedAt"])]
)
data class GsmCallLogEntity(
    @PrimaryKey val id: String,
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

@Entity(tableName = "gsm_whitelist_meta")
data class GsmWhitelistMetaEntity(
    @PrimaryKey val accountId: String,
    val version: Long = 0L,
    val lastLocalSyncAt: Long = 0L,
    val lastServerSyncAt: Long = 0L,
    val pendingRefresh: Boolean = false
)

@Entity(tableName = "schedules")
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: String,
    val name: String,
    val daysJson: String,
    val openTime: String,
    val closeTime: String,
    val enabled: Boolean,
    val scope: String
)

@Entity(tableName = "action_logs")
data class ActionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: String,
    val userId: String,
    val userEmail: String,
    val action: String,
    val detail: String,
    val success: Boolean,
    val timestamp: Long
)

@Entity(tableName = "rfid_tags")
data class RfidTagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: String,
    val label: String,
    val tagCode: String,
    val enabled: Boolean
)

@Entity(tableName = "lpr_plates")
data class LprPlateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: String,
    val label: String,
    val plate: String,
    val enabled: Boolean
)
