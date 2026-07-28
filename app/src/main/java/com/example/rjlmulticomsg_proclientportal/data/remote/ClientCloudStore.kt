package com.example.rjlmulticomsg_proclientportal.data.remote

import android.content.Context
import android.util.Log
import com.example.rjlmulticomsg_proclientportal.BuildConfig
import com.example.rjlmulticomsg_proclientportal.domain.model.ClientAccount
import com.example.rjlmulticomsg_proclientportal.domain.model.ConnectionType
import com.example.rjlmulticomsg_proclientportal.domain.model.GsmCallLog
import com.example.rjlmulticomsg_proclientportal.domain.model.GsmCaller
import com.example.rjlmulticomsg_proclientportal.domain.model.GsmCallerRole
import com.example.rjlmulticomsg_proclientportal.domain.model.GsmDeviceStatus
import com.example.rjlmulticomsg_proclientportal.domain.model.ModuleType
import com.example.rjlmulticomsg_proclientportal.domain.model.UserRole
import com.example.rjlmulticomsg_proclientportal.domain.phone.PhoneNumberNormalizer
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await

/**
 * Firebase Auth + Firestore bridge for the client portal.
 *
 * Collections (named maintenancejobs DB on iiii-7b9e8):
 *   clientAccounts/{accountId}
 *   clientAccounts/{accountId}/gsmCallers/{callerId}
 *   clientAccounts/{accountId}/gsmDevices/{deviceId}
 *   clientAccounts/{accountId}/gsmCallLogs/{logId}
 *   clientUsers/{uid}
 *   clientActionLogs/{autoId}
 */
class ClientCloudStore(
    private val appContext: Context
) {
    private val tag = "ClientCloudStore"

    private val auth: FirebaseAuth? by lazy {
        runCatching { FirebaseAuth.getInstance() }.getOrNull()
    }

    private val db: FirebaseFirestore? by lazy {
        // Project uses named DB "gsmsimcared" (not "(default)").
        runCatching {
            FirebaseFirestore.getInstance(
                com.google.firebase.FirebaseApp.getInstance(),
                "gsmsimcared",
            )
        }.getOrNull()
    }

    private val functions: FirebaseFunctions? by lazy {
        runCatching {
            FirebaseFunctions.getInstance(
                FirebaseApp.getInstance(),
                "australia-southeast1"
            )
        }.getOrNull()
    }

    val isAvailable: Boolean
        get() = auth != null && db != null

    data class CloudProfile(
        val uid: String,
        val email: String,
        val displayName: String,
        val role: UserRole,
        val enabled: Boolean,
        val account: ClientAccount,
        val accounts: List<ClientAccount> = emptyList(),
        val enabledModules: Set<ModuleType>
    )

    suspend fun signIn(email: String, password: String): Result<FirebaseUser> {
        val a = auth ?: return Result.failure(IllegalStateException("Firebase Auth unavailable"))
        return try {
            val result = a.signInWithEmailAndPassword(email.trim(), password).await()
            val user = result.user
                ?: return Result.failure(IllegalStateException("No user returned"))
            Result.success(user)
        } catch (e: Exception) {
            Log.w(tag, "Firebase sign-in failed")
            Result.failure(e)
        }
    }

    /** Sign in with a Google ID token from Google Sign-In / Credential Manager. */
    suspend fun signInWithGoogleIdToken(idToken: String): Result<FirebaseUser> {
        val a = auth ?: return Result.failure(IllegalStateException("Firebase Auth unavailable"))
        if (idToken.isBlank()) {
            return Result.failure(IllegalStateException("Google sign-in did not return a token."))
        }
        return try {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val result = a.signInWithCredential(credential).await()
            val user = result.user
                ?: return Result.failure(IllegalStateException("No user returned from Google"))
            Result.success(user)
        } catch (e: Exception) {
            Log.w(tag, "Google sign-in failed", e)
            Result.failure(e)
        }
    }

    /** Email a Firebase password-reset link. Always succeeds generically to avoid email enumeration. */
    suspend fun sendPasswordReset(email: String): Result<Unit> {
        val a = auth ?: return Result.failure(IllegalStateException("Firebase Auth unavailable"))
        val normalized = email.trim().lowercase()
        if (normalized.isBlank() || '@' !in normalized) {
            return Result.failure(IllegalArgumentException("Enter a valid email address."))
        }
        return try {
            a.sendPasswordResetEmail(normalized).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(tag, "Password reset failed", e)
            // Still return success-style UX for unknown users to avoid leaking accounts,
            // but surface real network/config problems.
            val msg = e.message.orEmpty().lowercase()
            when {
                "network" in msg || "unable to resolve" in msg || "unavailable" in msg ->
                    Result.failure(IllegalStateException("Network error. Check your connection and try again."))
                "blocked" in msg || "too many" in msg ->
                    Result.failure(IllegalStateException("Too many attempts. Wait a few minutes and try again."))
                else -> Result.success(Unit)
            }
        }
    }

    fun signOut() {
        runCatching { auth?.signOut() }
    }

    fun currentFirebaseUser(): FirebaseUser? = auth?.currentUser

    /**
     * Load clientUsers/{uid} + clientAccounts/{accountId}.
     * Does not let the client invent accountIds/roles for existing users.
     */
    suspend fun loadOrProvisionProfile(user: FirebaseUser): Result<CloudProfile> {
        val firestore = db ?: return Result.failure(IllegalStateException("Firestore unavailable"))
        val email = user.email?.trim()?.lowercase().orEmpty()
        if (email.isBlank()) {
            return Result.failure(IllegalStateException("Firebase user has no email"))
        }

        return try {
            val userDoc = firestore.collection(COL_USERS).document(user.uid).get().await()
            if (userDoc.exists()) {
                val primaryId = userDoc.getString("accountId").orEmpty()
                if (primaryId.isBlank()) {
                    return Result.failure(IllegalStateException("clientUsers doc missing accountId"))
                }
                val userEnabled = userDoc.getBoolean("enabled") ?: true
                if (!userEnabled) {
                    return Result.failure(IllegalStateException("This account is disabled. Contact RJL."))
                }
                val extraIds = parseAccountIds(userDoc.get("accountIds"))
                val allIds = (listOf(primaryId) + extraIds).distinct()
                val accounts = allIds.mapNotNull { id -> loadAccount(id) }
                val account = accounts.firstOrNull { it.id == primaryId }
                    ?: accounts.firstOrNull()
                    ?: return Result.failure(IllegalStateException("No client accounts for user"))
                if (!account.enabled) {
                    return Result.failure(IllegalStateException("This property is disabled. Contact RJL."))
                }
                val role = UserRole.from(userDoc.getString("role") ?: "MEMBER")
                val modules = parseModules(userDoc.get("modules"))
                    .ifEmpty { loadAccountModules(account.id) }
                val displayName = userDoc.getString("displayName")
                    ?: user.displayName
                    ?: email
                return Result.success(
                    CloudProfile(
                        uid = user.uid,
                        email = email,
                        displayName = displayName,
                        role = role,
                        enabled = userEnabled,
                        account = account,
                        accounts = accounts,
                        enabledModules = modules
                    )
                )
            }

            // First cloud login in debug only — attach to demo account for installer testing.
            if (!BuildConfig.DEBUG) {
                return Result.failure(
                    IllegalStateException(
                        "No client profile found. Ask RJL to link your login to a property."
                    )
                )
            }

            val accountId = DEFAULT_ACCOUNT_ID
            val account = loadAccount(accountId) ?: defaultAccount(accountId)
            val profile = mapOf(
                "accountId" to accountId,
                "accountIds" to listOf(accountId),
                "email" to email,
                "displayName" to (user.displayName ?: email),
                "role" to UserRole.OWNER.name,
                "enabled" to true,
                "modules" to emptyList<String>(),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            firestore.collection(COL_USERS).document(user.uid)
                .set(profile, SetOptions.merge())
                .await()

            Result.success(
                CloudProfile(
                    uid = user.uid,
                    email = email,
                    displayName = user.displayName ?: email,
                    role = UserRole.OWNER,
                    enabled = true,
                    account = account,
                    accounts = listOf(account),
                    enabledModules = emptySet()
                )
            )
        } catch (e: Exception) {
            Log.e(tag, "loadOrProvisionProfile failed", e)
            Result.failure(e)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseAccountIds(raw: Any?): List<String> {
        return when (raw) {
            is List<*> -> raw.mapNotNull { it?.toString()?.takeIf { s -> s.isNotBlank() } }
            is String -> raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            else -> emptyList()
        }
    }

    suspend fun saveModules(uid: String, accountId: String, modules: Set<ModuleType>) {
        val firestore = db ?: return
        try {
            // Modules live on the account. Clients must not rewrite clientUsers.modules
            // (security rules lock that field on self-updates).
            firestore.collection(COL_ACCOUNTS).document(accountId).set(
                mapOf(
                    "modules" to modules.map { it.name },
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()
            // Touch user updatedAt only — no privileged field changes
            firestore.collection(COL_USERS).document(uid).set(
                mapOf("updatedAt" to FieldValue.serverTimestamp()),
                SetOptions.merge()
            ).await()
        } catch (e: Exception) {
            Log.w(tag, "saveModules cloud sync failed: ${e.message}")
        }
    }

    suspend fun saveOnboardingSite(
        accountId: String,
        siteName: String,
        address: String,
        notes: String,
        connectionType: String,
        onboardingComplete: Boolean
    ) {
        val firestore = db ?: return
        try {
            firestore.collection(COL_ACCOUNTS).document(accountId).set(
                mapOf(
                    "siteName" to siteName,
                    "address" to address,
                    "notes" to notes,
                    "connectionType" to connectionType,
                    "onboardingComplete" to onboardingComplete,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()
        } catch (e: Exception) {
            Log.w(tag, "saveOnboardingSite failed: ${e.message}")
        }
    }

    suspend fun changePassword(current: String, newPassword: String): Result<Unit> {
        val user = auth?.currentUser
            ?: return Result.failure(IllegalStateException("Not signed in with Firebase"))
        val email = user.email
            ?: return Result.failure(IllegalStateException("No email on Firebase user"))
        return try {
            val credential = EmailAuthProvider.getCredential(email, current)
            user.reauthenticate(credential).await()
            user.updatePassword(newPassword).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createFamilyMember(
        ownerUid: String,
        accountId: String,
        name: String,
        email: String,
        tempPassword: String
    ): Result<String> {
        val firestore = db ?: return Result.failure(IllegalStateException("Firestore unavailable"))
        val primary = FirebaseApp.getInstance()
        val secondaryName = "clientPortalSecondary"
        val secondary = try {
            FirebaseApp.getApps(appContext).firstOrNull { it.name == secondaryName }
                ?: FirebaseApp.initializeApp(appContext, primary.options, secondaryName)
                ?: return Result.failure(IllegalStateException("Could not init secondary FirebaseApp"))
        } catch (e: Exception) {
            return Result.failure(e)
        }

        val secondaryAuth = FirebaseAuth.getInstance(secondary)
        return try {
            val created = secondaryAuth.createUserWithEmailAndPassword(
                email.trim().lowercase(),
                tempPassword
            ).await()
            val uid = created.user?.uid
                ?: return Result.failure(IllegalStateException("No uid for new member"))

            firestore.collection(COL_USERS).document(uid).set(
                mapOf(
                    "accountId" to accountId,
                    "email" to email.trim().lowercase(),
                    "displayName" to name.trim().ifBlank { email },
                    "role" to UserRole.MEMBER.name,
                    "enabled" to true,
                    "modules" to emptyList<String>(),
                    "invitedBy" to ownerUid,
                    "mustChangePassword" to true,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            secondaryAuth.signOut()
            Result.success(uid)
        } catch (e: Exception) {
            runCatching { secondaryAuth.signOut() }
            Result.failure(e)
        }
    }

    suspend fun logCloudAction(
        accountId: String,
        userId: String,
        userEmail: String,
        action: String,
        detail: String,
        success: Boolean
    ) {
        val firestore = db ?: return
        try {
            firestore.collection(COL_LOGS).add(
                mapOf(
                    "accountId" to accountId,
                    "userId" to userId,
                    "userEmail" to userEmail,
                    "action" to action,
                    "detail" to detail,
                    "success" to success,
                    "timestamp" to FieldValue.serverTimestamp(),
                    "source" to "client_portal"
                )
            ).await()
        } catch (e: Exception) {
            Log.w(tag, "cloud log failed: ${e.message}")
        }
    }

    // ── GSM callers ──────────────────────────────────────────────────────────

    suspend fun fetchGsmCallers(accountId: String): Result<List<GsmCaller>> {
        val firestore = db ?: return Result.failure(IllegalStateException("Firestore unavailable"))
        return try {
            val snap = firestore.collection(COL_ACCOUNTS).document(accountId)
                .collection(COL_GSM_CALLERS)
                .get()
                .await()
            val list = snap.documents.mapNotNull { doc -> doc.toGsmCaller(accountId) }
            Result.success(list)
        } catch (e: Exception) {
            Log.w(tag, "fetchGsmCallers: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun upsertGsmCaller(accountId: String, caller: GsmCaller): Result<GsmCaller> {
        val firestore = db ?: return Result.failure(IllegalStateException("Firestore unavailable"))
        val uid = auth?.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Not signed in"))
        return try {
            val ref = if (caller.id.isBlank()) {
                firestore.collection(COL_ACCOUNTS).document(accountId)
                    .collection(COL_GSM_CALLERS).document()
            } else {
                firestore.collection(COL_ACCOUNTS).document(accountId)
                    .collection(COL_GSM_CALLERS).document(caller.id)
            }
            val isNew = !ref.get().await().exists()
            val data = mapOf(
                "displayName" to caller.displayName.take(120),
                "phoneNumberE164" to caller.phoneNumberE164,
                "enabled" to caller.enabled,
                "validFrom" to caller.validFrom?.let { Timestamp(it / 1000, 0) },
                "validUntil" to caller.validUntil?.let { Timestamp(it / 1000, 0) },
                "role" to caller.role.name,
                "notes" to caller.notes.take(500),
                "linkedUserId" to caller.linkedUserId,
                "createdBy" to (caller.createdBy.ifBlank { uid }),
                "updatedBy" to uid,
                "updatedAt" to FieldValue.serverTimestamp()
            ).toMutableMap()
            if (caller.createdAt <= 0L || isNew) {
                data["createdAt"] = FieldValue.serverTimestamp()
            }
            // Caller mutation and version bump commit together, so a controller
            // can never observe a new version without the matching caller data.
            val accountRef = firestore.collection(COL_ACCOUNTS).document(accountId)
            firestore.batch().apply {
                set(ref, data, SetOptions.merge())
                set(
                    accountRef,
                mapOf(
                    "whitelistVersion" to FieldValue.increment(1),
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
                )
            }.commit().await()
            val written = ref.get().await().toGsmCaller(accountId)
                ?: caller.copy(id = ref.id, pendingSync = false)
            Result.success(written)
        } catch (e: Exception) {
            Log.w(tag, "upsertGsmCaller: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun deleteGsmCaller(accountId: String, callerId: String): Result<Unit> {
        val firestore = db ?: return Result.failure(IllegalStateException("Firestore unavailable"))
        return try {
            val accountRef = firestore.collection(COL_ACCOUNTS).document(accountId)
            val callerRef = accountRef.collection(COL_GSM_CALLERS).document(callerId)
            firestore.batch().apply {
                delete(callerRef)
                set(
                    accountRef,
                    mapOf(
                        "whitelistVersion" to FieldValue.increment(1),
                        "updatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
            }.commit().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    data class SmsCampaignResult(
        val campaignId: String,
        val queued: Int,
        val skipped: Int
    )

    data class RemoteGateTestResult(
        val commandId: String,
        val deviceId: String,
        val expiresInSeconds: Int
    )

    suspend fun requestRemoteGateTest(): Result<RemoteGateTestResult> {
        val client = functions
            ?: return Result.failure(IllegalStateException("Remote controller service unavailable"))
        return try {
            @Suppress("UNCHECKED_CAST")
            val data = client.getHttpsCallable("requestRemoteGateTest")
                .call()
                .await()
                .data as? Map<String, Any?>
                ?: return Result.failure(IllegalStateException("Invalid controller response"))
            Result.success(
                RemoteGateTestResult(
                    commandId = data["commandId"]?.toString().orEmpty(),
                    deviceId = data["deviceId"]?.toString().orEmpty(),
                    expiresInSeconds = (data["expiresInSeconds"] as? Number)?.toInt() ?: 300
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createSmsCampaign(
        callerIds: List<String>,
        message: String
    ): Result<SmsCampaignResult> {
        val client = functions
            ?: return Result.failure(IllegalStateException("Messaging service unavailable"))
        return try {
            @Suppress("UNCHECKED_CAST")
            val data = client
                .getHttpsCallable("createSmsCampaign")
                .call(mapOf("callerIds" to callerIds, "message" to message))
                .await()
                .data as? Map<String, Any?>
                ?: return Result.failure(IllegalStateException("Invalid messaging response"))
            Result.success(
                SmsCampaignResult(
                    campaignId = data["campaignId"]?.toString().orEmpty(),
                    queued = (data["queued"] as? Number)?.toInt() ?: 0,
                    skipped = (data["skipped"] as? Number)?.toInt() ?: 0
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchGsmDevices(accountId: String): Result<List<GsmDeviceStatus>> {
        val firestore = db ?: return Result.failure(IllegalStateException("Firestore unavailable"))
        return try {
            val snap = firestore.collection(COL_ACCOUNTS).document(accountId)
                .collection(COL_GSM_DEVICES)
                .get()
                .await()
            Result.success(snap.documents.mapNotNull { it.toGsmDevice(accountId) })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchGsmCallLogs(accountId: String, limit: Long = 50): Result<List<GsmCallLog>> {
        val firestore = db ?: return Result.failure(IllegalStateException("Firestore unavailable"))
        return try {
            val snap = firestore.collection(COL_ACCOUNTS).document(accountId)
                .collection(COL_GSM_CALL_LOGS)
                .orderBy("receivedAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .limit(limit)
                .get()
                .await()
            Result.success(snap.documents.mapNotNull { it.toGsmCallLog(accountId) })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Request ESP32 whitelist refresh by writing a flag the backend/device watches.
     */
    suspend fun requestWhitelistRefresh(accountId: String): Result<Unit> {
        val firestore = db ?: return Result.failure(IllegalStateException("Firestore unavailable"))
        return try {
            firestore.collection(COL_ACCOUNTS).document(accountId).set(
                mapOf(
                    "whitelistRefreshRequestedAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun loadAccount(accountId: String): ClientAccount? {
        val firestore = db ?: return null
        val doc = firestore.collection(COL_ACCOUNTS).document(accountId).get().await()
        if (!doc.exists()) return null
        val rawGsm = doc.getString("gsmNumber").orEmpty()
        val gsm = when (val r = PhoneNumberNormalizer.normalize(rawGsm)) {
            is PhoneNumberNormalizer.Result.Valid -> r.e164
            else -> rawGsm // keep invalid as-is for admin correction
        }
        return ClientAccount(
            id = accountId,
            siteName = doc.getString("siteName") ?: "",
            portalBaseUrl = doc.getString("portalBaseUrl")
                ?: doc.getString("portalUrl")
                ?: if (BuildConfig.DEBUG) BuildConfig.DEMO_PORTAL_URL else "",
            gsmNumber = gsm.ifBlank {
                if (BuildConfig.DEBUG) BuildConfig.DEMO_GSM_NUMBER else ""
            },
            wifiHost = doc.getString("wifiHost") ?: "",
            openPath = doc.getString("openPath") ?: "/gate/open",
            address = doc.getString("address") ?: "",
            region = doc.getString("region") ?: "",
            notes = doc.getString("notes") ?: "",
            connectionType = ConnectionType.from(
                doc.getString("connectionType") ?: "HYBRID"
            ),
            onboardingComplete = doc.getBoolean("onboardingComplete") ?: false,
            gsmAllowAnyCaller = false,
            enabled = doc.getBoolean("enabled") ?: true,
            timezone = doc.getString("timezone") ?: "Australia/Melbourne",
            whitelistVersion = doc.getLong("whitelistVersion") ?: 0L,
            createdAt = doc.getLong("createdAt")
                ?: (doc.getTimestamp("createdAt")?.toDate()?.time)
                ?: System.currentTimeMillis()
        )
    }

    private fun defaultAccount(accountId: String) = ClientAccount(
        id = accountId,
        siteName = "",
        portalBaseUrl = if (BuildConfig.DEBUG) BuildConfig.DEMO_PORTAL_URL else "",
        gsmNumber = if (BuildConfig.DEBUG) BuildConfig.DEMO_GSM_NUMBER else "",
        wifiHost = if (BuildConfig.DEBUG) "100.103.206.69" else "",
        openPath = "/gate/open",
        address = "",
        region = "",
        connectionType = ConnectionType.HYBRID,
        onboardingComplete = false,
        gsmAllowAnyCaller = false,
        enabled = true
    )

    private suspend fun loadAccountModules(accountId: String): Set<ModuleType> {
        val firestore = db ?: return emptySet()
        return try {
            val doc = firestore.collection(COL_ACCOUNTS).document(accountId).get().await()
            parseModules(doc.get("modules"))
        } catch (_: Exception) {
            emptySet()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseModules(raw: Any?): Set<ModuleType> {
        val list = when (raw) {
            is List<*> -> raw.mapNotNull { it?.toString() }
            is String -> raw.split(",").map { it.trim() }
            else -> emptyList()
        }
        return list.mapNotNull { name ->
            runCatching { ModuleType.from(name) }.getOrNull()
        }.toSet()
    }

    private fun com.google.firebase.firestore.DocumentSnapshot.toGsmCaller(accountId: String): GsmCaller? {
        val id = id
        val name = getString("displayName") ?: getString("name") ?: return null
        val phone = getString("phoneNumberE164") ?: getString("phone") ?: return null
        val e164 = when (val r = PhoneNumberNormalizer.normalize(phone)) {
            is PhoneNumberNormalizer.Result.Valid -> r.e164
            else -> phone
        }
        return GsmCaller(
            id = id,
            accountId = accountId,
            displayName = name,
            phoneNumberE164 = e164,
            enabled = getBoolean("enabled") ?: true,
            validFrom = timestampMs("validFrom"),
            validUntil = timestampMs("validUntil"),
            role = GsmCallerRole.from(getString("role")),
            notes = getString("notes").orEmpty(),
            linkedUserId = getString("linkedUserId"),
            createdBy = getString("createdBy").orEmpty(),
            createdAt = timestampMs("createdAt") ?: 0L,
            updatedBy = getString("updatedBy").orEmpty(),
            updatedAt = timestampMs("updatedAt") ?: 0L,
            serverUpdatedAt = timestampMs("updatedAt") ?: 0L,
            pendingSync = false
        )
    }

    private fun com.google.firebase.firestore.DocumentSnapshot.toGsmDevice(accountId: String): GsmDeviceStatus? {
        val location = getGeoPoint("lastLocation")
        return GsmDeviceStatus(
            deviceId = id,
            accountId = accountId,
            deviceName = getString("deviceName").orEmpty(),
            enabled = getBoolean("enabled") ?: true,
            firmwareVersion = getString("firmwareVersion").orEmpty(),
            whitelistVersion = getLong("whitelistVersion") ?: 0L,
            lastSeenAt = timestampMs("lastSeenAt") ?: 0L,
            signalStrength = getLong("signalStrength")?.toInt(),
            networkRegistered = getBoolean("networkRegistered") ?: false,
            modemModel = getString("modemModel").orEmpty(),
            operator = getString("operator").orEmpty(),
            radioTechnology = getString("radioTechnology").orEmpty(),
            latitude = location?.latitude,
            longitude = location?.longitude,
            gnssAltitudeMetres = getDouble("gnssAltitudeMetres"),
            gnssSpeedKnots = getDouble("gnssSpeedKnots"),
            gnssHeadingDegrees = getDouble("gnssHeadingDegrees"),
            gnssCapturedAt = timestampMs("gnssCapturedAt") ?: 0L,
            gnssSource = getString("gnssSource").orEmpty(),
            lastSyncAt = timestampMs("lastSyncAt") ?: 0L,
            lastError = getString("lastError"),
            gsmDeviceTimeMs = timestampMs("gsmDeviceTimeMs") ?: 0L
        )
    }

    private fun com.google.firebase.firestore.DocumentSnapshot.toGsmCallLog(accountId: String): GsmCallLog? {
        return GsmCallLog(
            id = id,
            accountId = accountId,
            deviceId = getString("deviceId").orEmpty(),
            callerNumberE164 = getString("callerNumberE164") ?: "WITHHELD",
            matchedCallerId = getString("matchedCallerId"),
            matchedCallerName = getString("matchedCallerName"),
            authorised = getBoolean("authorised") ?: false,
            relayTriggered = getBoolean("relayTriggered") ?: false,
            rejectionReason = getString("rejectionReason").orEmpty(),
            receivedAt = timestampMs("receivedAt") ?: 0L,
            uploadedAt = timestampMs("uploadedAt") ?: 0L,
            signalStrength = getLong("signalStrength")?.toInt()
        )
    }

    private fun com.google.firebase.firestore.DocumentSnapshot.timestampMs(field: String): Long? {
        getTimestamp(field)?.toDate()?.time?.let { return it }
        getLong(field)?.let { return it }
        return null
    }

    companion object {
        const val COL_ACCOUNTS = "clientAccounts"
        const val COL_USERS = "clientUsers"
        const val COL_LOGS = "clientActionLogs"
        const val COL_GSM_CALLERS = "gsmCallers"
        const val COL_GSM_DEVICES = "gsmDevices"
        const val COL_GSM_CALL_LOGS = "gsmCallLogs"
        const val DEFAULT_ACCOUNT_ID = "acct_mildura_wmc"
    }
}
