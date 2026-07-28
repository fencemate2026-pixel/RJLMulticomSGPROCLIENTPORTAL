package com.example.rjlmulticomsg_proclientportal.domain.model

enum class ModuleType(val label: String, val shortLabel: String) {
    WIFI("Wi‑Fi Module", "Wi‑Fi"),
    GSM("GSM Module", "GSM"),
    RFID("RFID Module", "RFID"),
    LPR("License Plate Recognition", "LPR");

    companion object {
        fun from(raw: String): ModuleType =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: WIFI
    }
}

enum class UserRole {
    OWNER,
    MEMBER;

    companion object {
        fun from(raw: String): UserRole =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: MEMBER
    }
}

enum class ScheduleScope {
    WIFI,
    GSM,
    BOTH;

    companion object {
        fun from(raw: String): ScheduleScope =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: BOTH
    }
}

enum class ConnectionType {
    WIFI,
    GSM,
    HYBRID;

    companion object {
        fun from(raw: String): ConnectionType =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: HYBRID
    }
}

/** Client picks primary gate system at onboarding. */
enum class GateSystem {
    WIFI,
    GSM
}

data class ClientAccount(
    val id: String,
    val siteName: String,
    val portalBaseUrl: String,
    /** Property SIM7600 number — preferably E.164; may be blank or invalid until RJL fixes it. */
    val gsmNumber: String,
    val wifiHost: String = "",
    val openPath: String = "/gate/open",
    val address: String = "",
    val region: String = "",
    val notes: String = "",
    val connectionType: ConnectionType = ConnectionType.HYBRID,
    val onboardingComplete: Boolean = false,
    /**
     * Legacy local-only flag. ESP32 whitelist always requires listed callers;
     * this never authorises unknown numbers on the controller.
     */
    val gsmAllowAnyCaller: Boolean = false,
    val enabled: Boolean = true,
    val timezone: String = "Australia/Melbourne",
    val whitelistVersion: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
) {
    val subtitle: String
        get() = listOfNotNull(
            address.takeIf { it.isNotBlank() } ?: region.takeIf { it.isNotBlank() },
            connectionType.name
        ).joinToString(" · ")

    val gsmNumberValid: Boolean
        get() = com.example.rjlmulticomsg_proclientportal.domain.phone.PhoneNumberNormalizer
            .normalize(gsmNumber) is
            com.example.rjlmulticomsg_proclientportal.domain.phone.PhoneNumberNormalizer.Result.Valid

    val gsmNumberE164: String?
        get() = when (
            val r = com.example.rjlmulticomsg_proclientportal.domain.phone.PhoneNumberNormalizer
                .normalize(gsmNumber)
        ) {
            is com.example.rjlmulticomsg_proclientportal.domain.phone.PhoneNumberNormalizer.Result.Valid -> r.e164
            else -> null
        }
}

data class ClientUser(
    val id: String,
    val accountId: String,
    val email: String,
    val displayName: String,
    val role: UserRole,
    val enabled: Boolean = true,
    val mustChangePassword: Boolean = false,
    val modules: List<ModuleType> = emptyList()
)

data class ModulePreference(
    val module: ModuleType,
    val enabled: Boolean
)

data class GateSchedule(
    val id: Long = 0,
    val accountId: String,
    val name: String,
    val days: List<Int>,
    val openTime: String,
    val closeTime: String,
    val enabled: Boolean = true,
    val scope: ScheduleScope = ScheduleScope.BOTH
)

data class ActionLogEntry(
    val id: Long = 0,
    val accountId: String,
    val userId: String,
    val userEmail: String,
    val action: String,
    val detail: String,
    val success: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

data class RfidTag(
    val id: Long = 0,
    val accountId: String,
    val label: String,
    val tagCode: String,
    val enabled: Boolean = true
)

data class LprPlate(
    val id: Long = 0,
    val accountId: String,
    val label: String,
    val plate: String,
    val enabled: Boolean = true
)

data class SessionState(
    val user: ClientUser?,
    val account: ClientAccount?,
    val modules: List<ModulePreference>,
    val sites: List<ClientAccount> = emptyList(),
    val isLoading: Boolean = true,
    val dataStale: Boolean = false,
    val lastSyncAt: Long = 0L
) {
    val isLoggedIn: Boolean get() = user != null && account != null
    /** First-run setup: gate type + site details (+ GSM callers). */
    val needsOnboarding: Boolean get() = isLoggedIn && account?.onboardingComplete != true
    val hasModuleSelection: Boolean get() = modules.any { it.enabled }
    val enabledModules: List<ModuleType> get() = modules.filter { it.enabled }.map { it.module }
    val isOwner: Boolean get() = user?.role == UserRole.OWNER
    val accountEnabled: Boolean get() = account?.enabled != false
    val userEnabled: Boolean get() = user?.enabled != false
}
