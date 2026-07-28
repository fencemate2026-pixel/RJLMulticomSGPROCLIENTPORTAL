package com.example.rjlmulticomsg_proclientportal.data.local

import com.example.rjlmulticomsg_proclientportal.domain.model.ActionLogEntry
import com.example.rjlmulticomsg_proclientportal.domain.model.ClientAccount
import com.example.rjlmulticomsg_proclientportal.domain.model.ClientUser
import com.example.rjlmulticomsg_proclientportal.domain.model.ConnectionType
import com.example.rjlmulticomsg_proclientportal.domain.model.GateSchedule
import com.example.rjlmulticomsg_proclientportal.domain.model.GsmCallLog
import com.example.rjlmulticomsg_proclientportal.domain.model.GsmCaller
import com.example.rjlmulticomsg_proclientportal.domain.model.GsmCallerRole
import com.example.rjlmulticomsg_proclientportal.domain.model.GsmDeviceStatus
import com.example.rjlmulticomsg_proclientportal.domain.model.GsmWhitelistMeta
import com.example.rjlmulticomsg_proclientportal.domain.model.LprPlate
import com.example.rjlmulticomsg_proclientportal.domain.model.ModulePreference
import com.example.rjlmulticomsg_proclientportal.domain.model.ModuleType
import com.example.rjlmulticomsg_proclientportal.domain.model.RfidTag
import com.example.rjlmulticomsg_proclientportal.domain.model.ScheduleScope
import com.example.rjlmulticomsg_proclientportal.domain.model.UserRole
import org.json.JSONArray

fun AccountEntity.toDomain() = ClientAccount(
    id = id,
    siteName = siteName,
    portalBaseUrl = portalBaseUrl,
    gsmNumber = gsmNumber,
    wifiHost = wifiHost,
    openPath = openPath,
    address = address,
    region = region,
    notes = notes,
    connectionType = ConnectionType.from(connectionType),
    onboardingComplete = onboardingComplete,
    gsmAllowAnyCaller = gsmAllowAnyCaller,
    enabled = enabled,
    timezone = timezone,
    whitelistVersion = whitelistVersion,
    createdAt = createdAt
)

fun GsmCallerEntity.toDomain() = GsmCaller(
    id = id,
    accountId = accountId,
    displayName = displayName,
    phoneNumberE164 = phoneNumberE164,
    enabled = enabled,
    validFrom = validFrom,
    validUntil = validUntil,
    role = GsmCallerRole.from(role),
    notes = notes,
    linkedUserId = linkedUserId,
    createdBy = createdBy,
    createdAt = createdAt,
    updatedBy = updatedBy,
    updatedAt = updatedAt,
    localUpdatedAt = localUpdatedAt,
    serverUpdatedAt = serverUpdatedAt,
    pendingSync = pendingSync
)

fun GsmCaller.toEntity() = GsmCallerEntity(
    id = id,
    accountId = accountId,
    displayName = displayName,
    phoneNumberE164 = phoneNumberE164,
    enabled = enabled,
    validFrom = validFrom,
    validUntil = validUntil,
    role = role.name,
    notes = notes,
    linkedUserId = linkedUserId,
    createdBy = createdBy,
    createdAt = createdAt,
    updatedBy = updatedBy,
    updatedAt = updatedAt,
    localUpdatedAt = localUpdatedAt,
    serverUpdatedAt = serverUpdatedAt,
    pendingSync = pendingSync
)

fun GsmDeviceEntity.toDomain() = GsmDeviceStatus(
    deviceId = deviceId,
    accountId = accountId,
    deviceName = deviceName,
    enabled = enabled,
    firmwareVersion = firmwareVersion,
    whitelistVersion = whitelistVersion,
    lastSeenAt = lastSeenAt,
    signalStrength = signalStrength,
    networkRegistered = networkRegistered,
    modemModel = modemModel,
    operator = operator,
    radioTechnology = radioTechnology,
    latitude = latitude,
    longitude = longitude,
    gnssAltitudeMetres = gnssAltitudeMetres,
    gnssSpeedKnots = gnssSpeedKnots,
    gnssHeadingDegrees = gnssHeadingDegrees,
    gnssCapturedAt = gnssCapturedAt,
    gnssSource = gnssSource,
    lastSyncAt = lastSyncAt,
    lastError = lastError,
    gsmDeviceTimeMs = gsmDeviceTimeMs,
    localUpdatedAt = localUpdatedAt
)

fun GsmCallLogEntity.toDomain() = GsmCallLog(
    id = id,
    accountId = accountId,
    deviceId = deviceId,
    callerNumberE164 = callerNumberE164,
    matchedCallerId = matchedCallerId,
    matchedCallerName = matchedCallerName,
    authorised = authorised,
    relayTriggered = relayTriggered,
    rejectionReason = rejectionReason,
    receivedAt = receivedAt,
    uploadedAt = uploadedAt,
    signalStrength = signalStrength
)

fun GsmWhitelistMetaEntity.toDomain() = GsmWhitelistMeta(
    accountId = accountId,
    version = version,
    lastLocalSyncAt = lastLocalSyncAt,
    lastServerSyncAt = lastServerSyncAt,
    pendingRefresh = pendingRefresh
)

fun UserEntity.toDomain() = ClientUser(
    id = id,
    accountId = accountId,
    email = email,
    displayName = displayName,
    role = UserRole.from(role),
    enabled = enabled,
    mustChangePassword = mustChangePassword,
    modules = modulesJson.toModuleList()
)

fun AccountModuleEntity.toDomain() = ModulePreference(
    module = ModuleType.from(moduleType),
    enabled = enabled
)

fun ScheduleEntity.toDomain() = GateSchedule(
    id = id,
    accountId = accountId,
    name = name,
    days = daysJson.toIntList(),
    openTime = openTime,
    closeTime = closeTime,
    enabled = enabled,
    scope = ScheduleScope.from(scope)
)

fun GateSchedule.toEntity() = ScheduleEntity(
    id = id,
    accountId = accountId,
    name = name,
    daysJson = days.toJsonArray(),
    openTime = openTime,
    closeTime = closeTime,
    enabled = enabled,
    scope = scope.name
)

fun ActionLogEntity.toDomain() = ActionLogEntry(
    id = id,
    accountId = accountId,
    userId = userId,
    userEmail = userEmail,
    action = action,
    detail = detail,
    success = success,
    timestamp = timestamp
)

fun RfidTagEntity.toDomain() = RfidTag(
    id = id,
    accountId = accountId,
    label = label,
    tagCode = tagCode,
    enabled = enabled
)

fun LprPlateEntity.toDomain() = LprPlate(
    id = id,
    accountId = accountId,
    label = label,
    plate = plate,
    enabled = enabled
)

private fun String.toIntList(): List<Int> {
    if (isBlank()) return emptyList()
    return try {
        val arr = JSONArray(this)
        buildList {
            for (i in 0 until arr.length()) add(arr.getInt(i))
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun List<Int>.toJsonArray(): String {
    val arr = JSONArray()
    forEach { arr.put(it) }
    return arr.toString()
}

private fun String.toModuleList(): List<ModuleType> {
    if (isBlank()) return emptyList()
    return try {
        val arr = JSONArray(this)
        buildList {
            for (i in 0 until arr.length()) {
                val name = arr.optString(i)
                if (name.isNotBlank()) add(ModuleType.from(name))
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

fun List<ModuleType>.toModulesJson(): String {
    val arr = JSONArray()
    forEach { arr.put(it.name) }
    return arr.toString()
}
