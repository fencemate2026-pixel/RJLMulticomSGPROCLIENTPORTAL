package com.example.rjlmulticomsg_proclientportal.data.repo

import android.content.Context
import android.util.Log
import com.example.rjlmulticomsg_proclientportal.BuildConfig
import com.example.rjlmulticomsg_proclientportal.data.local.AccountEntity
import com.example.rjlmulticomsg_proclientportal.data.local.AccountModuleEntity
import com.example.rjlmulticomsg_proclientportal.data.local.ActionLogEntity
import com.example.rjlmulticomsg_proclientportal.data.local.AppDatabase
import com.example.rjlmulticomsg_proclientportal.data.local.DatabaseSeeder
import com.example.rjlmulticomsg_proclientportal.data.local.GsmCallLogEntity
import com.example.rjlmulticomsg_proclientportal.data.local.GsmCallerEntity
import com.example.rjlmulticomsg_proclientportal.data.local.GsmDeviceEntity
import com.example.rjlmulticomsg_proclientportal.data.local.GsmWhitelistMetaEntity
import com.example.rjlmulticomsg_proclientportal.data.local.LprPlateEntity
import com.example.rjlmulticomsg_proclientportal.data.local.RfidTagEntity
import com.example.rjlmulticomsg_proclientportal.data.local.UserEntity
import com.example.rjlmulticomsg_proclientportal.data.local.UserSiteEntity
import com.example.rjlmulticomsg_proclientportal.data.local.toDomain
import com.example.rjlmulticomsg_proclientportal.data.local.toEntity
import com.example.rjlmulticomsg_proclientportal.data.local.toModulesJson
import com.example.rjlmulticomsg_proclientportal.data.remote.ClientCloudStore
import com.example.rjlmulticomsg_proclientportal.data.remote.GateOpenResult
import com.example.rjlmulticomsg_proclientportal.data.remote.GsmGateOpener
import com.example.rjlmulticomsg_proclientportal.data.remote.GsmOpenResult
import com.example.rjlmulticomsg_proclientportal.data.remote.WifiGateClient
import com.example.rjlmulticomsg_proclientportal.data.security.PasswordHasher
import com.example.rjlmulticomsg_proclientportal.data.session.SessionStore
import com.example.rjlmulticomsg_proclientportal.domain.model.ActionLogEntry
import com.example.rjlmulticomsg_proclientportal.domain.model.ActionTypes
import com.example.rjlmulticomsg_proclientportal.domain.model.ClientAccount
import com.example.rjlmulticomsg_proclientportal.domain.model.ClientUser
import com.example.rjlmulticomsg_proclientportal.domain.model.ConnectionType
import com.example.rjlmulticomsg_proclientportal.domain.model.GateSchedule
import com.example.rjlmulticomsg_proclientportal.domain.model.GateSystem
import com.example.rjlmulticomsg_proclientportal.domain.model.GsmCallLog
import com.example.rjlmulticomsg_proclientportal.domain.model.GsmCaller
import com.example.rjlmulticomsg_proclientportal.domain.model.GsmCallerRole
import com.example.rjlmulticomsg_proclientportal.domain.model.GsmDeviceStatus
import com.example.rjlmulticomsg_proclientportal.domain.model.GsmWhitelistMeta
import com.example.rjlmulticomsg_proclientportal.domain.model.LprPlate
import com.example.rjlmulticomsg_proclientportal.domain.model.ModulePreference
import com.example.rjlmulticomsg_proclientportal.domain.model.ModuleType
import com.example.rjlmulticomsg_proclientportal.domain.model.RfidTag
import com.example.rjlmulticomsg_proclientportal.domain.model.SessionState
import com.example.rjlmulticomsg_proclientportal.domain.model.UserRole
import com.example.rjlmulticomsg_proclientportal.domain.phone.PhoneNumberNormalizer
import com.example.rjlmulticomsg_proclientportal.security.GateScheduleManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class PortalRepository(
    private val db: AppDatabase,
    private val sessionStore: SessionStore,
    private val cloud: ClientCloudStore,
    private val wifiClient: WifiGateClient = WifiGateClient(),
    private val gsmOpener: GsmGateOpener = GsmGateOpener()
) {
    private val _session = MutableStateFlow(
        SessionState(null, null, emptyList(), sites = emptyList(), isLoading = true)
    )
    val session: StateFlow<SessionState> = _session.asStateFlow()

    private var cloudSession = false
    private val gsmOpenInFlight = AtomicBoolean(false)

    val firebaseAvailable: Boolean get() = cloud.isAvailable

    suspend fun bootstrap() {
        DatabaseSeeder.ensureSeeded(db)
        val fbUser = cloud.currentFirebaseUser()
        if (fbUser != null) {
            val profile = cloud.loadOrProvisionProfile(fbUser).getOrNull()
            if (profile != null) {
                mirrorCloudProfile(profile)
                sessionStore.setLoggedIn(profile.uid, profile.email, remember = true)
                cloudSession = true
                restoreUser(profile.uid)
                syncGsmFromCloud(profile.account.id)
                return
            }
        }
        val userId = sessionStore.userId.first()
        if (userId != null) {
            cloudSession = false
            restoreUser(userId)
        } else {
            _session.value = SessionState(
                null, null, emptyList(), sites = emptyList(), isLoading = false
            )
        }
    }

    suspend fun rememberedEmail(): String = sessionStore.rememberEmail.first().orEmpty()
    suspend fun rememberMe(): Boolean = sessionStore.rememberMe.first()

    suspend fun login(email: String, password: String, remember: Boolean): Result<ClientUser> {
        val normalized = email.trim().lowercase()
        val plain = password // do not trim passwords (only email)

        // Debug installer account: always re-seed and force local login so you can never get locked out.
        if (BuildConfig.DEBUG &&
            BuildConfig.ALLOW_DEMO_LOGIN &&
            normalized == DatabaseSeeder.DEMO_EMAIL.lowercase() &&
            plain == DatabaseSeeder.DEMO_PASSWORD
        ) {
            return forceDemoLogin(remember)
        }

        // Local accounts first in debug (works offline)
        if (BuildConfig.DEBUG) {
            val localFirst = tryLocalLogin(normalized, plain, remember)
            if (localFirst.isSuccess) return localFirst
        } else if (normalized == DatabaseSeeder.DEMO_EMAIL.lowercase()) {
            return Result.failure(
                IllegalArgumentException("Demo login is disabled in production builds.")
            )
        }

        if (cloud.isAvailable) {
            val fb = cloud.signIn(normalized, plain)
            if (fb.isSuccess) {
                return finalizeCloudLogin(
                    user = fb.getOrThrow(),
                    remember = remember,
                    detail = "Signed in via Firebase email"
                )
            }
            // Cloud failed — in debug, reseed demo once more in case local DB was wiped mid-session
            if (BuildConfig.DEBUG) {
                val retry = tryLocalLogin(normalized, plain, remember)
                if (retry.isSuccess) return retry
            }
            val msg = fb.exceptionOrNull()?.message
                ?.takeIf { it.isNotBlank() }
                ?: "Unknown username or password."
            return Result.failure(IllegalArgumentException(friendlyAuthError(msg)))
        }

        if (BuildConfig.DEBUG) {
            // Ensure seed exists even if bootstrap failed earlier
            runCatching { DatabaseSeeder.ensureSeeded(db) }
            return tryLocalLogin(normalized, plain, remember)
        }
        return Result.failure(
            IllegalArgumentException("Cloud login unavailable. Check your connection.")
        )
    }

    /** Bulletproof debug login — rewrites demo user + account then restores session. */
    private suspend fun forceDemoLogin(remember: Boolean): Result<ClientUser> {
        return try {
            DatabaseSeeder.ensureSeeded(db)
            val entity = db.userDao().getByEmail(DatabaseSeeder.DEMO_EMAIL)
                ?: return Result.failure(
                    IllegalStateException("Demo user missing after seed. Reinstall the app.")
                )
            // Guarantee a local password works even if a cloud session overwrote the hash.
            val withPassword = entity.copy(
                passwordHash = PasswordHasher.hash(DatabaseSeeder.DEMO_PASSWORD),
                enabled = true
            )
            db.userDao().upsert(withPassword)
            sessionStore.setLoggedIn(withPassword.id, withPassword.email, remember)
            cloudSession = false
            // Clear any sticky Firebase session so bootstrap won't bounce you out
            cloud.signOut()
            restoreUser(withPassword.id)
            val sessionOk = _session.value.isLoggedIn
            if (!sessionOk) {
                // Last resort: attach commercial or demo account by id
                val accountId = withPassword.accountId
                    .ifBlank { DatabaseSeeder.COMMERCIAL_ACCOUNT_ID }
                ensureModulesForAccount(accountId)
                val account = db.accountDao().getById(accountId)
                    ?: db.accountDao().getById(DatabaseSeeder.DEMO_ACCOUNT_ID)
                if (account == null) {
                    return Result.failure(
                        IllegalStateException("Demo property missing. Clear app data and reinstall.")
                    )
                }
                db.userSiteDao().upsert(UserSiteEntity(withPassword.id, account.id))
                db.userDao().upsert(withPassword.copy(accountId = account.id))
                restoreUser(withPassword.id)
            }
            if (!_session.value.isLoggedIn) {
                return Result.failure(
                    IllegalStateException("Login produced no session. Clear app storage and try again.")
                )
            }
            log(
                _session.value.account?.id ?: withPassword.accountId,
                withPassword.id,
                withPassword.email,
                ActionTypes.LOGIN,
                "Signed in (debug demo)",
                true
            )
            val user = _session.value.user ?: return Result.failure(
                IllegalStateException("Session established but user is null. Clear app data.")
            )
            Result.success(user)
        } catch (e: Exception) {
            Log.e("PortalRepository", "forceDemoLogin failed", e)
            Result.failure(
                IllegalStateException("Demo login failed: ${e.message ?: e.javaClass.simpleName}")
            )
        }
    }

    /**
     * Complete Google Sign-In after the UI obtains a Google ID token.
     */
    suspend fun loginWithGoogleIdToken(idToken: String, remember: Boolean = true): Result<ClientUser> {
        if (!cloud.isAvailable) {
            return Result.failure(
                IllegalArgumentException("Cloud login unavailable. Check your connection.")
            )
        }
        val fb = cloud.signInWithGoogleIdToken(idToken)
        if (fb.isFailure) {
            return Result.failure(
                IllegalArgumentException(
                    friendlyAuthError(
                        fb.exceptionOrNull()?.message ?: "Google sign-in failed."
                    )
                )
            )
        }
        return finalizeCloudLogin(
            user = fb.getOrThrow(),
            remember = remember,
            detail = "Signed in via Google"
        )
    }

    /** Send Firebase password-reset email for the given address. */
    suspend fun sendPasswordReset(email: String): Result<Unit> {
        if (!cloud.isAvailable) {
            return Result.failure(
                IllegalStateException("Password reset needs internet / Firebase.")
            )
        }
        return cloud.sendPasswordReset(email)
    }

    private suspend fun finalizeCloudLogin(
        user: com.google.firebase.auth.FirebaseUser,
        remember: Boolean,
        detail: String
    ): Result<ClientUser> {
        val profileResult = cloud.loadOrProvisionProfile(user)
        if (profileResult.isFailure) {
            cloud.signOut()
            val profileErr = profileResult.exceptionOrNull()?.message
            return Result.failure(
                IllegalArgumentException(
                    friendlyAuthError(profileErr ?: "No client profile for this login.")
                )
            )
        }
        val profile = profileResult.getOrThrow()
        val account = profile.account
        mirrorCloudProfile(profile)
        if (BuildConfig.DEBUG &&
            profile.email.equals(DatabaseSeeder.DEMO_EMAIL, ignoreCase = true)
        ) {
            DatabaseSeeder.ensureSeeded(db)
        }
        sessionStore.setLoggedIn(profile.uid, profile.email, remember)
        cloudSession = true
        restoreUser(profile.uid)
        syncGsmFromCloud(account.id)
        log(
            account.id, profile.uid, profile.email,
            ActionTypes.LOGIN,
            detail,
            true
        )
        return Result.success(
            ClientUser(
                id = profile.uid,
                accountId = account.id,
                email = profile.email,
                displayName = profile.displayName,
                role = profile.role,
                enabled = profile.enabled,
                modules = profile.enabledModules.toList()
            )
        )
    }

    private fun friendlyAuthError(raw: String): String {
        val m = raw.lowercase()
        return when {
            "disabled" in m -> raw
            "no client profile" in m || "link your login" in m ->
                "This Google/email account is not linked to a property yet. Ask RJL to link it."
            "password is invalid" in m || "wrong-password" in m ||
                "invalid-credential" in m || "user-not-found" in m ||
                "malformed" in m -> "Unknown username or password."
            "12500" in m || "developer_error" in m || "10:" in m ->
                "Google Sign-In is not configured for this app build (SHA-1 / OAuth). Use email login or contact RJL IT."
            "permission" in m || "permission_denied" in m ->
                "You do not have permission for this action."
            "network" in m || "unable to resolve" in m ->
                "Network error talking to Firebase. Check connection."
            else -> raw
        }
    }

    private suspend fun tryLocalLogin(
        email: String,
        password: String,
        remember: Boolean
    ): Result<ClientUser> {
        val entity = db.userDao().getByEmail(email)
            ?: return Result.failure(IllegalArgumentException("Unknown username or password."))
        if (!entity.enabled) {
            return Result.failure(IllegalArgumentException("This account is disabled. Contact RJL."))
        }
        if (entity.passwordHash == FIREBASE_MARKER) {
            return Result.failure(
                IllegalArgumentException("This account uses cloud login. Check internet and try again.")
            )
        }
        if (!PasswordHasher.verify(password, entity.passwordHash)) {
            return Result.failure(IllegalArgumentException("Unknown username or password."))
        }
        sessionStore.setLoggedIn(entity.id, entity.email, remember)
        cloudSession = false
        restoreUser(entity.id)
        log(entity.accountId, entity.id, entity.email, ActionTypes.LOGIN, "Signed in (local)", true)
        return Result.success(entity.toDomain())
    }

    private suspend fun mirrorCloudProfile(profile: ClientCloudStore.CloudProfile) {
        val accounts = profile.accounts.ifEmpty { listOf(profile.account) }
        for (acc in accounts) {
            val previous = db.accountDao().getById(acc.id)
            db.accountDao().upsert(
                AccountEntity(
                    id = acc.id,
                    siteName = acc.siteName.ifBlank { previous?.siteName.orEmpty() },
                    portalBaseUrl = acc.portalBaseUrl,
                    gsmNumber = acc.gsmNumber,
                    wifiHost = acc.wifiHost,
                    openPath = acc.openPath,
                    address = acc.address.ifBlank { previous?.address.orEmpty() },
                    region = acc.region.ifBlank { previous?.region.orEmpty() },
                    notes = acc.notes.ifBlank { previous?.notes.orEmpty() },
                    connectionType = acc.connectionType.name,
                    onboardingComplete = previous?.onboardingComplete
                        ?: acc.onboardingComplete,
                    gsmAllowAnyCaller = false,
                    enabled = acc.enabled,
                    timezone = acc.timezone,
                    whitelistVersion = acc.whitelistVersion,
                    createdAt = acc.createdAt
                )
            )
            if (db.moduleDao().list(acc.id).isEmpty()) {
                val enabled = if (acc.id == profile.account.id && profile.enabledModules.isNotEmpty()) {
                    profile.enabledModules
                } else {
                    emptySet()
                }
                db.moduleDao().upsertAll(
                    ModuleType.entries.map {
                        AccountModuleEntity(acc.id, it.name, enabled = it in enabled)
                    }
                )
            }
        }
        db.userDao().upsert(
            UserEntity(
                id = profile.uid,
                accountId = profile.account.id,
                email = profile.email,
                passwordHash = FIREBASE_MARKER,
                displayName = profile.displayName,
                role = profile.role.name,
                enabled = profile.enabled,
                mustChangePassword = false,
                modulesJson = profile.enabledModules.toList().toModulesJson()
            )
        )
        db.userSiteDao().upsertAll(
            accounts.map { UserSiteEntity(profile.uid, it.id) }
        )
        if (profile.enabledModules.isNotEmpty()) {
            db.moduleDao().upsertAll(
                ModuleType.entries.map {
                    AccountModuleEntity(
                        accountId = profile.account.id,
                        moduleType = it.name,
                        enabled = it in profile.enabledModules
                    )
                }
            )
        }
        sessionStore.setActiveAccountId(profile.account.id)
    }

    /**
     * Pull GSM callers / devices / logs from Firestore into Room.
     * Never overwrites local pending-sync rows with older server data.
     */
    suspend fun syncGsmFromCloud(accountId: String? = null) {
        val aid = accountId ?: _session.value.account?.id ?: return
        if (!cloudSession || !cloud.isAvailable) {
            _session.update { it.copy(dataStale = true) }
            return
        }
        val now = System.currentTimeMillis()
        var stale = false

        val callersResult = cloud.fetchGsmCallers(aid)
        if (callersResult.isSuccess) {
            val remote = callersResult.getOrThrow()
            val pendingIds = db.gsmCallerDao().listPendingSync()
                .filter { it.accountId == aid }
                .map { it.id }
                .toSet()
            val localById = db.gsmCallerDao().list(aid).associateBy { it.id }
            for (r in remote) {
                if (r.id in pendingIds) {
                    val local = localById[r.id]
                    if (local != null && local.localUpdatedAt > r.serverUpdatedAt) continue
                }
                val existing = localById[r.id]
                if (existing != null &&
                    existing.serverUpdatedAt > 0 &&
                    r.serverUpdatedAt > 0 &&
                    existing.serverUpdatedAt > r.serverUpdatedAt &&
                    !existing.pendingSync
                ) {
                    continue
                }
                db.gsmCallerDao().upsert(
                    GsmCallerEntity(
                        id = r.id,
                        accountId = r.accountId,
                        displayName = r.displayName,
                        phoneNumberE164 = r.phoneNumberE164,
                        enabled = r.enabled,
                        validFrom = r.validFrom,
                        validUntil = r.validUntil,
                        role = r.role.name,
                        notes = r.notes,
                        linkedUserId = r.linkedUserId,
                        createdBy = r.createdBy,
                        createdAt = r.createdAt,
                        updatedBy = r.updatedBy,
                        updatedAt = r.updatedAt,
                        localUpdatedAt = now,
                        serverUpdatedAt = r.serverUpdatedAt,
                        pendingSync = false
                    )
                )
            }
            // A complete Firestore snapshot is authoritative. Remove callers
            // deleted remotely while preserving any explicit local pending row.
            val remoteIds = remote.map { it.id }.toSet()
            localById.values
                .filter { !it.pendingSync && it.id !in remoteIds }
                .forEach { db.gsmCallerDao().delete(it.id) }
        } else {
            stale = true
        }

        val devicesResult = cloud.fetchGsmDevices(aid)
        if (devicesResult.isSuccess) {
            for (d in devicesResult.getOrThrow()) {
                db.gsmDeviceDao().upsert(
                    GsmDeviceEntity(
                        deviceId = d.deviceId,
                        accountId = d.accountId,
                        deviceName = d.deviceName,
                        enabled = d.enabled,
                        firmwareVersion = d.firmwareVersion,
                        whitelistVersion = d.whitelistVersion,
                        lastSeenAt = d.lastSeenAt,
                        signalStrength = d.signalStrength,
                        networkRegistered = d.networkRegistered,
                        modemModel = d.modemModel,
                        operator = d.operator,
                        radioTechnology = d.radioTechnology,
                        latitude = d.latitude,
                        longitude = d.longitude,
                        gnssAltitudeMetres = d.gnssAltitudeMetres,
                        gnssSpeedKnots = d.gnssSpeedKnots,
                        gnssHeadingDegrees = d.gnssHeadingDegrees,
                        gnssCapturedAt = d.gnssCapturedAt,
                        gnssSource = d.gnssSource,
                        lastSyncAt = d.lastSyncAt,
                        lastError = d.lastError,
                        localUpdatedAt = now
                    )
                )
            }
        } else {
            stale = true
        }

        val logsResult = cloud.fetchGsmCallLogs(aid)
        if (logsResult.isSuccess) {
            for (l in logsResult.getOrThrow()) {
                db.gsmCallLogDao().upsert(
                    GsmCallLogEntity(
                        id = l.id,
                        accountId = l.accountId,
                        deviceId = l.deviceId,
                        callerNumberE164 = l.callerNumberE164,
                        matchedCallerId = l.matchedCallerId,
                        matchedCallerName = l.matchedCallerName,
                        authorised = l.authorised,
                        relayTriggered = l.relayTriggered,
                        rejectionReason = l.rejectionReason,
                        receivedAt = l.receivedAt,
                        uploadedAt = l.uploadedAt,
                        signalStrength = l.signalStrength
                    )
                )
            }
        }

        val account = db.accountDao().getById(aid)
        db.gsmWhitelistMetaDao().upsert(
            GsmWhitelistMetaEntity(
                accountId = aid,
                version = account?.whitelistVersion ?: 0L,
                lastLocalSyncAt = now,
                lastServerSyncAt = now,
                pendingRefresh = false
            )
        )
        _session.update { it.copy(dataStale = stale, lastSyncAt = now) }
        // Refresh account snapshot for whitelist version
        _session.value.user?.id?.let { restoreUser(it) }
    }

    suspend fun logout() {
        val s = _session.value
        if (s.user != null) {
            log(s.user.accountId, s.user.id, s.user.email, ActionTypes.LOGOUT, "Signed out", true)
        }
        cloud.signOut()
        cloudSession = false
        sessionStore.clearSession()
        _session.value = SessionState(
            null, null, emptyList(), sites = emptyList(), isLoading = false
        )
    }

    private suspend fun restoreUser(userId: String) {
        val user = db.userDao().getById(userId) ?: run {
            _session.value = SessionState(
                null, null, emptyList(), sites = emptyList(), isLoading = false
            )
            return
        }
        if (!user.enabled) {
            sessionStore.clearSession()
            _session.value = SessionState(
                null, null, emptyList(), sites = emptyList(), isLoading = false
            )
            return
        }
        var sites = db.userSiteDao().sitesForUser(userId).map { it.toDomain() }
        if (sites.isEmpty()) {
            val single = db.accountDao().getById(user.accountId)
                ?: db.accountDao().listAll().firstOrNull()
            if (single != null) {
                db.userSiteDao().upsert(UserSiteEntity(userId, single.id))
                db.userDao().upsert(user.copy(accountId = single.id))
                sites = listOf(single.toDomain())
            }
        }
        val preferredId = sessionStore.activeAccountId.first()
            ?: user.accountId
        var active = sites.firstOrNull { it.id == preferredId }
            ?: sites.firstOrNull { it.id == user.accountId }
            ?: sites.firstOrNull()

        // Never leave a signed-in user with null account — that looks like "login failed".
        if (active == null) {
            val fallback = db.accountDao().getById(user.accountId)
                ?: db.accountDao().listAll().firstOrNull()
            if (fallback != null) {
                db.userSiteDao().upsert(UserSiteEntity(userId, fallback.id))
                db.userDao().upsert(user.copy(accountId = fallback.id))
                active = fallback.toDomain()
                sites = listOf(active) + sites.filter { it.id != active.id }
            }
        }

        if (active != null && !active.enabled) {
            // Disabled property: still allow session in debug so installer isn't locked out.
            if (!BuildConfig.DEBUG) {
                sessionStore.clearSession()
                _session.value = SessionState(
                    null, null, emptyList(), sites = emptyList(), isLoading = false
                )
                return
            }
        }
        if (active != null && active.id != user.accountId) {
            db.userDao().update(user.copy(accountId = active.id))
        }
        val accountId = active?.id ?: user.accountId
        ensureModulesForAccount(accountId)
        val modules = db.moduleDao().list(accountId).map { it.toDomain() }
        val refreshed = db.userDao().getById(userId) ?: user
        _session.value = SessionState(
            user = refreshed.copy(accountId = accountId).toDomain(),
            account = active,
            modules = ensureAllModules(modules),
            sites = if (active != null && sites.none { it.id == active.id }) {
                listOf(active) + sites
            } else {
                sites
            },
            isLoading = false,
            dataStale = _session.value.dataStale,
            lastSyncAt = _session.value.lastSyncAt
        )
    }

    private suspend fun ensureModulesForAccount(accountId: String) {
        if (db.moduleDao().list(accountId).isEmpty()) {
            db.moduleDao().upsertAll(
                ModuleType.entries.map {
                    AccountModuleEntity(accountId, it.name, enabled = false)
                }
            )
        }
    }

    private fun ensureAllModules(existing: List<ModulePreference>): List<ModulePreference> {
        val map = existing.associateBy { it.module }
        return ModuleType.entries.map { type ->
            map[type] ?: ModulePreference(type, enabled = false)
        }
    }

    suspend fun saveModules(selected: Set<ModuleType>) {
        val s = requireSession()
        val entities = ModuleType.entries.map {
            AccountModuleEntity(s.account.id, it.name, enabled = it in selected)
        }
        db.moduleDao().upsertAll(entities)
        if (cloudSession) {
            cloud.saveModules(s.user.id, s.account.id, selected)
        }
        log(
            s.account.id, s.user.id, s.user.email,
            "MODULES",
            "Enabled: ${selected.joinToString { it.shortLabel }.ifBlank { "none" }}",
            true
        )
        refreshModules()
    }

    suspend fun completeOnboarding(
        gateSystem: GateSystem,
        siteName: String,
        address: String,
        notes: String,
        gsmAllowAnyCaller: Boolean,
        gsmCallers: List<Pair<String, String>>
    ): Result<Unit> {
        val s = requireSession()
        val name = siteName.trim().take(120)
        if (name.isBlank()) {
            return Result.failure(IllegalArgumentException("Enter a site / property name."))
        }
        val addr = address.trim().take(200)
        if (addr.isBlank()) {
            return Result.failure(IllegalArgumentException("Enter the site address."))
        }
        // Whitelist is mandatory for GSM controller safety — ignore allow-any for ESP32 path
        if (gateSystem == GateSystem.GSM && gsmCallers.isEmpty() && !gsmAllowAnyCaller) {
            return Result.failure(
                IllegalArgumentException("Add at least one authorised caller for the GSM whitelist.")
            )
        }
        if (gateSystem == GateSystem.WIFI && s.account.portalBaseUrl.isBlank()) {
            return Result.failure(
                IllegalArgumentException("No portal URL on file. Contact RJL to provision your Wi‑Fi site.")
            )
        }
        if (gateSystem == GateSystem.GSM) {
            when (val r = PhoneNumberNormalizer.normalize(s.account.gsmNumber)) {
                is PhoneNumberNormalizer.Result.Invalid ->
                    return Result.failure(
                        IllegalArgumentException(
                            "Property GSM number is invalid (${r.reason}). Contact RJL before finishing setup."
                        )
                    )
                is PhoneNumberNormalizer.Result.Valid -> Unit
            }
        }

        val connection = when (gateSystem) {
            GateSystem.WIFI -> ConnectionType.WIFI
            GateSystem.GSM -> ConnectionType.GSM
        }
        val enabledModules = when (gateSystem) {
            GateSystem.WIFI -> setOf(ModuleType.WIFI)
            GateSystem.GSM -> setOf(ModuleType.GSM)
        }

        val entity = db.accountDao().getById(s.account.id)
            ?: return Result.failure(IllegalStateException("Property not found."))
        val gsmE164 = when (val r = PhoneNumberNormalizer.normalize(entity.gsmNumber)) {
            is PhoneNumberNormalizer.Result.Valid -> r.e164
            else -> entity.gsmNumber
        }
        db.accountDao().upsert(
            entity.copy(
                siteName = name,
                address = addr,
                notes = notes.trim().take(500),
                connectionType = connection.name,
                onboardingComplete = true,
                gsmAllowAnyCaller = false,
                gsmNumber = gsmE164
            )
        )
        db.moduleDao().upsertAll(
            ModuleType.entries.map {
                AccountModuleEntity(s.account.id, it.name, enabled = it in enabledModules)
            }
        )

        if (gateSystem == GateSystem.GSM) {
            db.gsmCallerDao().deleteAllForAccount(s.account.id)
            for ((callerName, phone) in gsmCallers) {
                val n = callerName.trim().take(120)
                val e164 = when (val r = PhoneNumberNormalizer.normalize(phone)) {
                    is PhoneNumberNormalizer.Result.Valid -> r.e164
                    else -> continue
                }
                if (n.isBlank()) continue
                if (db.gsmCallerDao().getByPhone(s.account.id, e164) != null) continue
                val id = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                val local = GsmCaller(
                    id = id,
                    accountId = s.account.id,
                    displayName = n,
                    phoneNumberE164 = e164,
                    enabled = true,
                    role = if (s.user.role == UserRole.OWNER &&
                        n.equals(s.user.displayName, ignoreCase = true)
                    ) GsmCallerRole.OWNER else GsmCallerRole.MEMBER,
                    linkedUserId = null,
                    createdBy = s.user.id,
                    createdAt = now,
                    updatedBy = s.user.id,
                    updatedAt = now,
                    pendingSync = cloudSession
                )
                db.gsmCallerDao().upsert(local.toEntity().copy(pendingSync = cloudSession))
                if (cloudSession) {
                    cloud.upsertGsmCaller(s.account.id, local).onSuccess { written ->
                        db.gsmCallerDao().delete(id)
                        db.gsmCallerDao().upsert(
                            written.toEntity().copy(pendingSync = false, localUpdatedAt = now)
                        )
                    }
                }
            }
            bumpLocalWhitelistVersion(s.account.id, s.user)
        }

        if (cloudSession) {
            cloud.saveModules(s.user.id, s.account.id, enabledModules)
            cloud.saveOnboardingSite(
                s.account.id, name, addr, notes.trim().take(500),
                connection.name, true
            )
        }
        log(
            s.account.id, s.user.id, s.user.email,
            "ONBOARDING",
            "System=$gateSystem · $name · ${if (gateSystem == GateSystem.GSM) {
                "${gsmCallers.size} whitelist"
            } else "Wi‑Fi"}",
            true
        )
        restoreUser(s.user.id)
        return Result.success(Unit)
    }

    // ── GSM callers / devices / logs ─────────────────────────────────────────

    fun observeGsmCallers(accountId: String): Flow<List<GsmCaller>> =
        db.gsmCallerDao().observe(accountId).map { list -> list.map { it.toDomain() } }

    fun observeGsmDevices(accountId: String): Flow<List<GsmDeviceStatus>> =
        db.gsmDeviceDao().observe(accountId).map { list -> list.map { it.toDomain() } }

    suspend fun listGsmDevices(accountId: String): List<GsmDeviceStatus> =
        db.gsmDeviceDao().list(accountId).map { it.toDomain() }

    fun observeGsmCallLogs(accountId: String): Flow<List<GsmCallLog>> =
        db.gsmCallLogDao().observe(accountId).map { list -> list.map { it.toDomain() } }

    fun observeWhitelistMeta(accountId: String): Flow<GsmWhitelistMeta?> =
        db.gsmWhitelistMetaDao().observe(accountId).map { it?.toDomain() }

    suspend fun getOwnGsmCaller(): GsmCaller? {
        val s = _session.value
        val user = s.user ?: return null
        val accountId = s.account?.id ?: return null
        return db.gsmCallerDao().getByLinkedUser(accountId, user.id)?.toDomain()
    }

    data class CallerForm(
        val displayName: String,
        val phoneRaw: String,
        val role: GsmCallerRole = GsmCallerRole.MEMBER,
        val enabled: Boolean = true,
        val validFrom: Long? = null,
        val validUntil: Long? = null,
        val notes: String = "",
        val linkedUserId: String? = null
    )

    suspend fun addGsmCaller(form: CallerForm): Result<GsmCaller> {
        val s = requireOwnerSession()
        if (form.displayName.trim().isBlank()) {
            return Result.failure(IllegalArgumentException("Enter a name."))
        }
        val e164 = when (val r = PhoneNumberNormalizer.normalize(form.phoneRaw)) {
            is PhoneNumberNormalizer.Result.Valid -> r.e164
            is PhoneNumberNormalizer.Result.Invalid ->
                return Result.failure(IllegalArgumentException("Mobile number: ${r.reason}"))
        }
        if (db.gsmCallerDao().getByPhone(s.account.id, e164) != null) {
            return Result.failure(IllegalArgumentException("That number is already on the whitelist."))
        }
        if (form.validFrom != null && form.validUntil != null && form.validUntil < form.validFrom) {
            return Result.failure(IllegalArgumentException("Valid-until must be after valid-from."))
        }
        val now = System.currentTimeMillis()
        val draft = GsmCaller(
            id = UUID.randomUUID().toString(),
            accountId = s.account.id,
            displayName = form.displayName.trim().take(120),
            phoneNumberE164 = e164,
            enabled = form.enabled,
            validFrom = form.validFrom,
            validUntil = form.validUntil,
            role = form.role,
            notes = form.notes.trim().take(500),
            linkedUserId = form.linkedUserId,
            createdBy = s.user.id,
            createdAt = now,
            updatedBy = s.user.id,
            updatedAt = now,
            pendingSync = cloudSession
        )
        db.gsmCallerDao().upsert(draft.toEntity())
        if (cloudSession) {
            val cloudResult = cloud.upsertGsmCaller(s.account.id, draft)
            if (cloudResult.isSuccess) {
                val written = cloudResult.getOrThrow()
                db.gsmCallerDao().upsert(
                    written.toEntity().copy(pendingSync = false, localUpdatedAt = now)
                )
                log(
                    s.account.id, s.user.id, s.user.email,
                    ActionTypes.CALLER_ADDED,
                    "${written.displayName} (${PhoneNumberNormalizer.maskForLog(written.phoneNumberE164)})",
                    true
                )
                bumpLocalWhitelistVersion(s.account.id, s.user)
                return Result.success(written)
            } else {
                // Cloud sync failed - keep draft in DB marked as pending sync for retry
                db.gsmCallerDao().upsert(draft.toEntity().copy(pendingSync = true, localUpdatedAt = now))
                val error = cloudResult.exceptionOrNull()
                    ?: IllegalStateException("Caller was not saved to the controller cloud.")
                log(
                    s.account.id, s.user.id, s.user.email,
                    ActionTypes.CALLER_ADDED,
                    "${draft.displayName} (${error.message})",
                    false
                )
                return Result.failure(error)
            }
        }
        log(
            s.account.id, s.user.id, s.user.email,
            ActionTypes.CALLER_ADDED,
            "${draft.displayName} (${PhoneNumberNormalizer.maskForLog(e164)})",
            true
        )
        bumpLocalWhitelistVersion(s.account.id, s.user)
        return Result.success(draft)
    }

    /** Convenience for simple add from onboarding/legacy UI. */
    suspend fun addGsmCaller(name: String, phone: String): Result<Unit> =
        addGsmCaller(CallerForm(displayName = name, phoneRaw = phone)).map { }

    suspend fun updateGsmCaller(callerId: String, form: CallerForm): Result<GsmCaller> {
        val s = requireOwnerSession()
        val existing = db.gsmCallerDao().getById(callerId)
            ?: return Result.failure(IllegalArgumentException("Caller not found."))
        if (existing.accountId != s.account.id) {
            return Result.failure(IllegalArgumentException("Caller not on this property."))
        }
        val e164 = when (val r = PhoneNumberNormalizer.normalize(form.phoneRaw)) {
            is PhoneNumberNormalizer.Result.Valid -> r.e164
            is PhoneNumberNormalizer.Result.Invalid ->
                return Result.failure(IllegalArgumentException("Mobile number: ${r.reason}"))
        }
        val dup = db.gsmCallerDao().getByPhone(s.account.id, e164)
        if (dup != null && dup.id != callerId) {
            return Result.failure(IllegalArgumentException("That number is already on the whitelist."))
        }
        if (form.validFrom != null && form.validUntil != null && form.validUntil < form.validFrom) {
            return Result.failure(IllegalArgumentException("Valid-until must be after valid-from."))
        }
        val now = System.currentTimeMillis()
        val wasEnabled = existing.enabled
        val updated = existing.copy(
            displayName = form.displayName.trim().take(120),
            phoneNumberE164 = e164,
            enabled = form.enabled,
            validFrom = form.validFrom,
            validUntil = form.validUntil,
            role = form.role?.name ?: GsmCallerRole.MEMBER.name,
            notes = form.notes.trim().take(500),
            linkedUserId = form.linkedUserId ?: existing.linkedUserId,
            updatedBy = s.user.id,
            updatedAt = now,
            localUpdatedAt = now,
            pendingSync = cloudSession
        )
        if (cloudSession) {
            val result = cloud.upsertGsmCaller(s.account.id, updated.toDomain())
            if (result.isFailure) {
                return Result.failure(
                    result.exceptionOrNull()
                        ?: IllegalStateException("Caller change was not saved.")
                )
            }
            // Cloud sync succeeded, update DB
            val written = result.getOrThrow()
            db.gsmCallerDao().upsert(
                written.toEntity().copy(pendingSync = false, localUpdatedAt = now)
            )
        } else {
            // Local-only update
            db.gsmCallerDao().upsert(updated)
        }
        log(
            s.account.id, s.user.id, s.user.email,
            ActionTypes.CALLER_CHANGED,
            updated.displayName,
            true
        )
        if (wasEnabled != form.enabled) {
            log(
                s.account.id, s.user.id, s.user.email,
                if (form.enabled) ActionTypes.CALLER_ENABLED else ActionTypes.CALLER_DISABLED,
                updated.displayName,
                true
            )
        }
        bumpLocalWhitelistVersion(s.account.id, s.user)
        return Result.success(updated.toDomain())
    }

    suspend fun setGsmCallerEnabled(callerId: String, enabled: Boolean): Result<Unit> {
        val s = requireOwnerSession()
        val existing = db.gsmCallerDao().getById(callerId)
            ?: return Result.failure(IllegalArgumentException("Caller not found."))
        if (existing.accountId != s.account.id) {
            return Result.failure(IllegalArgumentException("Caller not on this property."))
        }
        val now = System.currentTimeMillis()
        val updated = existing.copy(
            enabled = enabled,
            updatedBy = s.user.id,
            updatedAt = now,
            localUpdatedAt = now,
            pendingSync = cloudSession
        )
        db.gsmCallerDao().upsert(updated)
        if (cloudSession) {
            val result = cloud.upsertGsmCaller(s.account.id, updated.toDomain())
            if (result.isFailure) {
                db.gsmCallerDao().upsert(existing)
                return Result.failure(
                    result.exceptionOrNull()
                        ?: IllegalStateException("Caller status was not saved.")
                )
            }
        }
        log(
            s.account.id, s.user.id, s.user.email,
            if (enabled) ActionTypes.CALLER_ENABLED else ActionTypes.CALLER_DISABLED,
            existing.displayName,
            true
        )
        bumpLocalWhitelistVersion(s.account.id, s.user)
        return Result.success(Unit)
    }

    suspend fun deleteGsmCaller(id: String, name: String): Result<Unit> {
        val s = requireOwnerSession()
        val existing = db.gsmCallerDao().getById(id)
            ?: return Result.failure(IllegalArgumentException("Caller not found."))
        if (existing.accountId != s.account.id) {
            return Result.failure(IllegalArgumentException("Caller not on this property."))
        }
        if (cloudSession) {
            val result = cloud.deleteGsmCaller(s.account.id, id)
            if (result.isFailure) {
                return Result.failure(
                    result.exceptionOrNull()
                        ?: IllegalStateException("Caller was not deleted.")
                )
            }
        }
        db.gsmCallerDao().delete(id)
        log(s.account.id, s.user.id, s.user.email, ActionTypes.CALLER_DELETED, name, true)
        bumpLocalWhitelistVersion(s.account.id, s.user)
        return Result.success(Unit)
    }

    /** @deprecated Long id no longer used — prefer [deleteGsmCaller] with string id. */
    suspend fun deleteGsmCaller(id: Long, name: String) {
        deleteGsmCaller(id.toString(), name)
    }

    suspend fun requestWhitelistRefresh(): Result<Unit> {
        val s = requireOwnerSession()
        val meta = db.gsmWhitelistMetaDao().get(s.account.id)
            ?: GsmWhitelistMetaEntity(accountId = s.account.id)
        db.gsmWhitelistMetaDao().upsert(meta.copy(pendingRefresh = true))
        if (cloudSession) {
            cloud.requestWhitelistRefresh(s.account.id).getOrElse {
                return Result.failure(it)
            }
        }
        log(
            s.account.id, s.user.id, s.user.email,
            ActionTypes.WHITELIST_REFRESH_REQUESTED,
            "v${meta.version}",
            true
        )
        return Result.success(Unit)
    }

    suspend fun sendSms(
        callerIds: List<String>,
        message: String
    ): Result<ClientCloudStore.SmsCampaignResult> {
        val s = requireOwnerSession()
        val cleanMessage = message.trim()
        if (!cloudSession) {
            return Result.failure(IllegalStateException("An online login is required to send SMS."))
        }
        if (cleanMessage.isBlank() || cleanMessage.length > 160) {
            return Result.failure(IllegalArgumentException("Message must be 1–160 characters."))
        }
        if (callerIds.isEmpty()) {
            return Result.failure(IllegalArgumentException("Select at least one recipient."))
        }
        val result = cloud.createSmsCampaign(callerIds.distinct().take(200), cleanMessage)
        log(
            s.account.id,
            s.user.id,
            s.user.email,
            if (result.isSuccess) "SMS_CAMPAIGN_QUEUED" else "SMS_CAMPAIGN_FAILED",
            result.fold(
                onSuccess = { "campaign=${it.campaignId} queued=${it.queued}" },
                onFailure = { it.message ?: "Messaging failed" }
            ),
            result.isSuccess
        )
        return result
    }

    suspend fun requestRemoteGateTest(): Result<ClientCloudStore.RemoteGateTestResult> {
        val s = requireOwnerSession()
        if (!cloudSession) {
            return Result.failure(
                IllegalStateException("An online owner login is required for a remote test.")
            )
        }
        val result = cloud.requestRemoteGateTest()
        log(
            s.account.id,
            s.user.id,
            s.user.email,
            if (result.isSuccess) "REMOTE_GATE_TEST_QUEUED" else "REMOTE_GATE_TEST_FAILED",
            result.fold(
                onSuccess = { "command=${it.commandId} device=${it.deviceId}" },
                onFailure = { it.message ?: "Remote test failed" }
            ),
            result.isSuccess
        )
        return result
    }

    private suspend fun bumpLocalWhitelistVersion(accountId: String, user: ClientUser) {
        val acc = db.accountDao().getById(accountId) ?: return
        val next = acc.whitelistVersion + 1
        db.accountDao().upsert(acc.copy(whitelistVersion = next))
        val meta = db.gsmWhitelistMetaDao().get(accountId)
            ?: GsmWhitelistMetaEntity(accountId = accountId)
        db.gsmWhitelistMetaDao().upsert(
            meta.copy(
                version = next,
                lastLocalSyncAt = System.currentTimeMillis(),
                pendingRefresh = true
            )
        )
        log(
            accountId, user.id, user.email,
            ActionTypes.WHITELIST_VERSION_CHANGED,
            "version=$next",
            true
        )
        restoreUser(user.id)
    }

    private suspend fun refreshModules() {
        val s = _session.value
        val accountId = s.account?.id ?: return
        val modules = ensureAllModules(db.moduleDao().list(accountId).map { it.toDomain() })
        _session.update { it.copy(modules = modules) }
    }

    fun observeLogs(accountId: String): Flow<List<ActionLogEntry>> =
        db.actionLogDao().observe(accountId).map { list -> list.map { it.toDomain() } }

    fun observeSchedules(accountId: String): Flow<List<GateSchedule>> =
        db.scheduleDao().observe(accountId).map { list -> list.map { it.toDomain() } }

    fun observePeople(accountId: String): Flow<List<ClientUser>> =
        db.userDao().observeByAccount(accountId).map { list -> list.map { it.toDomain() } }

    fun observeRfid(accountId: String): Flow<List<RfidTag>> =
        db.rfidDao().observe(accountId).map { list -> list.map { it.toDomain() } }

    fun observeLpr(accountId: String): Flow<List<LprPlate>> =
        db.lprDao().observe(accountId).map { list -> list.map { it.toDomain() } }

    suspend fun openWifiGate(): GateOpenResult {
        val s = requireSession()
        assertUserCanOperate(s)
        val result = wifiClient.openGate(
            portalBaseUrl = s.account.portalBaseUrl,
            openPath = s.account.openPath,
            simulateIfUnreachable = BuildConfig.DEBUG
        )
        log(s.account.id, s.user.id, s.user.email, ActionTypes.OPEN_WIFI, result.message, result.success)
        return result
    }

    /**
     * GSM open-gate with full pre-flight checks.
     * Logs REQUESTED / LAUNCHED / LAUNCH_FAILED.
     * Does **not** log relay success — that comes only from ESP32 events.
     */
    suspend fun openGsmGate(context: Context): GsmOpenResult {
        // Atomic check-and-set to prevent concurrent calls
        if (!gsmOpenInFlight.compareAndSet(false, true)) {
            return GsmOpenResult(false, "A GSM open request is already in progress.")
        }
        try {
            val s = try {
                requireSession()
            } catch (e: Exception) {
                return GsmOpenResult(false, e.message ?: "Not signed in.")
            }
            try {
                assertUserCanOperate(s)
            } catch (e: Exception) {
                log(
                    s.account.id, s.user.id, s.user.email,
                    ActionTypes.GSM_OPEN_REQUESTED,
                    e.message ?: "Denied",
                    false
                )
                return GsmOpenResult(false, e.message ?: "Not permitted.")
            }

            val modules = db.moduleDao().list(s.account.id).map { it.toDomain() }
            val accountGsm = modules.any { it.module == ModuleType.GSM && it.enabled }
            if (!accountGsm) {
                val msg = "GSM module is not enabled for this property."
                log(s.account.id, s.user.id, s.user.email, ActionTypes.GSM_OPEN_REQUESTED, msg, false)
                return GsmOpenResult(false, msg)
            }

            // User must have an active whitelist entry (linked user or owner with any active OWNER role entry)
            val own = db.gsmCallerDao().getByLinkedUser(s.account.id, s.user.id)?.toDomain()
            val authorised = when {
                own != null && own.isAuthorisedNow() -> true
                s.user.role == UserRole.OWNER -> {
                    // Owners may open if they appear as any active caller, or if they manage the site
                    // and have at least one active self-linked / matching entry — require a caller row.
                    val all = db.gsmCallerDao().list(s.account.id).map { it.toDomain() }
                    all.any { c ->
                        c.isAuthorisedNow() && (
                            c.linkedUserId == s.user.id ||
                                c.role == GsmCallerRole.OWNER
                            )
                    } || all.any { it.isAuthorisedNow() && s.user.role == UserRole.OWNER }
                }
                else -> false
            }
            // Tighten: MEMBER must have linked active entry; OWNER needs any active own-linked or role OWNER entry
            val canOpen = when (s.user.role) {
                UserRole.MEMBER -> own != null && own.isAuthorisedNow()
                UserRole.OWNER -> {
                    val list = db.gsmCallerDao().list(s.account.id).map { it.toDomain() }
                    val linked = list.firstOrNull { it.linkedUserId == s.user.id }
                    when {
                        linked != null -> linked.isAuthorisedNow()
                        // Owner without linked entry: allow if they created an OWNER-role entry for themselves by phone later;
                        // for demo/local, allow owner if whitelist has any active entry and they are OWNER
                        list.any { it.isAuthorisedNow() } -> true
                        else -> false
                    }
                }
            }
            if (!canOpen) {
                val msg = if (s.user.role == UserRole.MEMBER) {
                    "You do not have an active GSM whitelist entry. Ask the property owner."
                } else {
                    "No active GSM whitelist entry. Add an authorised caller first."
                }
                log(s.account.id, s.user.id, s.user.email, ActionTypes.GSM_OPEN_REQUESTED, msg, false)
                return GsmOpenResult(false, msg)
            }

            val e164 = s.account.gsmNumberE164
            if (e164 == null) {
                val msg = "Property GSM number is invalid. Contact RJL."
                log(s.account.id, s.user.id, s.user.email, ActionTypes.GSM_OPEN_REQUESTED, msg, false)
                return GsmOpenResult(false, msg)
            }

            log(
                s.account.id, s.user.id, s.user.email,
                ActionTypes.GSM_OPEN_REQUESTED,
                "Site ${s.account.siteName.ifBlank { s.account.id }}",
                true
            )

            val result = gsmOpener.openGate(context, e164)
            if (result.dialLaunched) {
                log(
                    s.account.id, s.user.id, s.user.email,
                    ActionTypes.GSM_CALL_LAUNCHED,
                    result.message,
                    true
                )
            } else {
                log(
                    s.account.id, s.user.id, s.user.email,
                    ActionTypes.GSM_CALL_LAUNCH_FAILED,
                    result.message,
                    false
                )
            }
            // Never claim relay success here
            return result
        } finally {
            gsmOpenInFlight.set(false)
        }
    }

    fun gsmHasCallPermission(context: Context) = gsmOpener.hasCallPermission(context)

    suspend fun addSchedule(schedule: GateSchedule) {
        val s = requireSession()
        db.scheduleDao().upsert(schedule.copy(accountId = s.account.id).toEntity())
        log(s.account.id, s.user.id, s.user.email, "SCHEDULE_ADD", schedule.name, true)
    }

    suspend fun deleteSchedule(id: Long, name: String) {
        val s = requireSession()
        db.scheduleDao().delete(id)
        log(s.account.id, s.user.id, s.user.email, "SCHEDULE_DELETE", name, true)
    }

    suspend fun toggleSchedule(id: Long, enabled: Boolean, name: String) {
        val s = requireSession()
        db.scheduleDao().setEnabled(id, enabled)
        log(
            s.account.id, s.user.id, s.user.email,
            "SCHEDULE_TOGGLE",
            "$name → ${if (enabled) "enabled" else "disabled"}",
            true
        )
    }

    suspend fun getSchedules(accountId: String): List<GateSchedule> {
        return db.scheduleDao().getByAccountId(accountId).map { it.toDomain() }
    }

    /**
     * Check if calls should currently be accepted by SIM7600.
     * Returns false during operating hours (6:30 AM - 6:00 PM) when relay is busy.
     * Returns true after hours (6:00 PM - 6:30 AM) when calls can trigger gate.
     */
    fun shouldAcceptCalls(): Boolean {
        return GateScheduleManager.shouldAcceptCalls()
    }

    /**
     * Check if gate is currently within operating hours.
     * Returns true during 6:30 AM - 6:00 PM (gate open, relay pulsing).
     * Returns false outside these hours (gate closed).
     */
    fun isWithinGateOperatingHours(): Boolean {
        return GateScheduleManager.isWithinOperatingHours()
    }

    /**
     * Get current gate status message.
     */
    fun getGateStatusMessage(): String {
        return GateScheduleManager.getStatusMessage()
    }

    suspend fun addFamilyMember(name: String, email: String, tempPassword: String): Result<ClientUser> {
        val s = requireSession()
        if (s.user.role != UserRole.OWNER) {
            return Result.failure(IllegalStateException("Only the property owner can add family or friends."))
        }
        val normalized = email.trim().lowercase()
        if (normalized.isBlank() || tempPassword.length < 6) {
            return Result.failure(IllegalArgumentException("Email and password (min 6 chars) are required."))
        }
        if (db.userDao().getByEmail(normalized) != null) {
            return Result.failure(IllegalArgumentException("That username/email is already in use on this phone."))
        }

        if (cloudSession && cloud.isAvailable) {
            val cloudResult = cloud.createFamilyMember(
                ownerUid = s.user.id,
                accountId = s.account.id,
                name = name,
                email = normalized,
                tempPassword = tempPassword
            )
            if (cloudResult.isSuccess) {
                val uid = cloudResult.getOrThrow()
                val entity = UserEntity(
                    id = uid,
                    accountId = s.account.id,
                    email = normalized,
                    passwordHash = FIREBASE_MARKER,
                    displayName = name.trim().ifBlank { normalized },
                    role = UserRole.MEMBER.name,
                    enabled = true,
                    mustChangePassword = true
                )
                db.userDao().upsert(entity)
                db.userSiteDao().upsert(UserSiteEntity(entity.id, s.account.id))
                log(
                    s.account.id, s.user.id, s.user.email,
                    "FAMILY_ADD",
                    "Cloud member ${entity.displayName} ($normalized)",
                    true
                )
                return Result.success(entity.toDomain())
            }
            val err = cloudResult.exceptionOrNull()?.message.orEmpty()
            if (err.contains("email-already-in-use", true) || err.contains("already in use", true)) {
                return Result.failure(IllegalArgumentException("That email is already registered in Firebase."))
            }
        }

        val entity = UserEntity(
            id = UUID.randomUUID().toString(),
            accountId = s.account.id,
            email = normalized,
            passwordHash = PasswordHasher.hash(tempPassword),
            displayName = name.trim().ifBlank { normalized },
            role = UserRole.MEMBER.name,
            enabled = true,
            mustChangePassword = true
        )
        return try {
            db.userDao().insert(entity)
            db.userSiteDao().upsert(UserSiteEntity(entity.id, s.account.id))
            log(
                s.account.id, s.user.id, s.user.email,
                "FAMILY_ADD",
                "Local member ${entity.displayName} ($normalized)",
                true
            )
            Result.success(entity.toDomain())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun removeMember(userId: String): Result<Unit> {
        val s = requireSession()
        if (s.user.role != UserRole.OWNER) {
            return Result.failure(IllegalStateException("Only the owner can remove users."))
        }
        if (userId == s.user.id) {
            return Result.failure(IllegalArgumentException("You cannot remove yourself."))
        }
        val target = db.userDao().getById(userId)
            ?: return Result.failure(IllegalArgumentException("User not found."))
        if (target.accountId != s.account.id) {
            return Result.failure(IllegalArgumentException("User not on this property."))
        }
        db.userDao().delete(userId)
        log(s.account.id, s.user.id, s.user.email, "FAMILY_REMOVE", target.email, true)
        return Result.success(Unit)
    }

    suspend fun changePassword(current: String, newPassword: String): Result<Unit> {
        val s = requireSession()
        if (newPassword.length < 6) {
            return Result.failure(IllegalArgumentException("New password must be at least 6 characters."))
        }

        if (cloudSession) {
            val cloudResult = cloud.changePassword(current, newPassword)
            if (cloudResult.isFailure) {
                return Result.failure(
                    IllegalArgumentException(
                        cloudResult.exceptionOrNull()?.message
                            ?: "Could not change cloud password."
                    )
                )
            }
            val entity = db.userDao().getById(s.user.id)
            if (entity != null) {
                db.userDao().update(entity.copy(mustChangePassword = false))
            }
            log(s.account.id, s.user.id, s.user.email, "PASSWORD_CHANGE", "Firebase password updated", true)
            restoreUser(s.user.id)
            return Result.success(Unit)
        }

        val entity = db.userDao().getById(s.user.id)
            ?: return Result.failure(IllegalStateException("Session expired."))
        if (!PasswordHasher.verify(current, entity.passwordHash)) {
            return Result.failure(IllegalArgumentException("Current password is incorrect."))
        }
        db.userDao().update(
            entity.copy(
                passwordHash = PasswordHasher.hash(newPassword),
                mustChangePassword = false
            )
        )
        log(s.account.id, s.user.id, s.user.email, "PASSWORD_CHANGE", "Local password updated", true)
        restoreUser(entity.id)
        return Result.success(Unit)
    }

    suspend fun addRfid(label: String, code: String) {
        val s = requireSession()
        db.rfidDao().upsert(
            RfidTagEntity(
                accountId = s.account.id,
                label = label.trim(),
                tagCode = code.trim(),
                enabled = true
            )
        )
        log(s.account.id, s.user.id, s.user.email, "RFID_ADD", "$label ($code)", true)
    }

    suspend fun deleteRfid(id: Long, label: String) {
        val s = requireSession()
        db.rfidDao().delete(id)
        log(s.account.id, s.user.id, s.user.email, "RFID_DELETE", label, true)
    }

    suspend fun addLpr(label: String, plate: String) {
        val s = requireSession()
        db.lprDao().upsert(
            LprPlateEntity(
                accountId = s.account.id,
                label = label.trim(),
                plate = plate.trim().uppercase(),
                enabled = true
            )
        )
        log(s.account.id, s.user.id, s.user.email, "LPR_ADD", "$label ($plate)", true)
    }

    suspend fun deleteLpr(id: Long, plate: String) {
        val s = requireSession()
        db.lprDao().delete(id)
        log(s.account.id, s.user.id, s.user.email, "LPR_DELETE", plate, true)
    }

    private suspend fun log(
        accountId: String,
        userId: String,
        userEmail: String,
        action: String,
        detail: String,
        success: Boolean
    ) {
        db.actionLogDao().insert(
            ActionLogEntity(
                accountId = accountId,
                userId = userId,
                userEmail = userEmail,
                action = action,
                detail = detail,
                success = success,
                timestamp = System.currentTimeMillis()
            )
        )
        if (cloudSession) {
            cloud.logCloudAction(accountId, userId, userEmail, action, detail, success)
        }
    }

    private fun assertUserCanOperate(s: SessionBundle) {
        if (!s.user.enabled) {
            throw IllegalStateException("Your user account is disabled.")
        }
        if (!s.account.enabled) {
            throw IllegalStateException("This property is disabled.")
        }
    }

    private fun requireSession(): SessionBundle {
        val s = _session.value
        val user = s.user
        val account = s.account
        if (user == null || account == null) {
            throw IllegalStateException("Not signed in.")
        }
        if (!user.enabled) throw IllegalStateException("Your user account is disabled.")
        if (!account.enabled) throw IllegalStateException("This property is disabled.")
        return SessionBundle(user, account)
    }

    private fun requireOwnerSession(): SessionBundle {
        val s = requireSession()
        if (s.user.role != UserRole.OWNER) {
            throw IllegalStateException("Only the property owner can manage GSM callers.")
        }
        return s
    }

    private data class SessionBundle(val user: ClientUser, val account: ClientAccount)

    companion object {
        private const val FIREBASE_MARKER = "firebase-auth"
    }
}
