package com.example.rjlmulticomsg_proclientportal.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.rjlmulticomsg_proclientportal.BuildConfig
import com.example.rjlmulticomsg_proclientportal.data.security.PasswordHasher
import com.example.rjlmulticomsg_proclientportal.domain.model.ConnectionType
import com.example.rjlmulticomsg_proclientportal.domain.model.ModuleType
import com.example.rjlmulticomsg_proclientportal.domain.model.UserRole
import com.example.rjlmulticomsg_proclientportal.domain.phone.PhoneNumberNormalizer
import java.util.UUID

@Database(
    entities = [
        AccountEntity::class,
        UserEntity::class,
        UserSiteEntity::class,
        AccountModuleEntity::class,
        GsmCallerEntity::class,
        GsmDeviceEntity::class,
        GsmCallLogEntity::class,
        GsmWhitelistMetaEntity::class,
        ScheduleEntity::class,
        ActionLogEntity::class,
        RfidTagEntity::class,
        LprPlateEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun userDao(): UserDao
    abstract fun userSiteDao(): UserSiteDao
    abstract fun moduleDao(): ModuleDao
    abstract fun gsmCallerDao(): GsmCallerDao
    abstract fun gsmDeviceDao(): GsmDeviceDao
    abstract fun gsmCallLogDao(): GsmCallLogDao
    abstract fun gsmWhitelistMetaDao(): GsmWhitelistMetaDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun actionLogDao(): ActionLogDao
    abstract fun rfidDao(): RfidDao
    abstract fun lprDao(): LprDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "sgpro_client_portal.db"
                )
                    .addCallback(SeedCallback())
                    // Pre-production schema changes are destructive until a deployed install
                    // base requires explicit migrations.
                    // until a production install base requires a hand-written Migration.
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
        }
    }
}

private class SeedCallback : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
    }
}

/**
 * Built-in multi-site catalogue — aligned with ProGate seed-portal-sites.js.
 * Demo login credentials are only used when [BuildConfig.DEBUG] is true.
 */
object DatabaseSeeder {
    const val DEMO_EMAIL = "client@rjl.com.au"
    const val DEMO_PASSWORD = "Client123!"
    /** Default active site (Mildura WMC Tailscale). */
    const val DEMO_ACCOUNT_ID = "acct_mildura_wmc"
    const val DEMO_PORTAL_URL = "http://100.103.206.69:5000"
    /**
     * Seeded GSM string as historically provisioned. May fail E.164 validation —
     * the app treats invalid property numbers as non-callable until RJL corrects them.
     */
    /** Demo gate SIM (site Multicom) — people dial this number. */
    const val DEMO_GSM_NUMBER = "0414371302"
    const val DEMO_WIFI_HOST = "100.103.206.69"
    const val DEMO_SITE_NAME = "Mildura Working Man's Club"
    /** Commercial multi-unit GSM site (contact sheet import). */
    const val COMMERCIAL_ACCOUNT_ID = CommercialWhitelistSeed.ACCOUNT_ID

    data class SeedSite(
        val id: String,
        val name: String,
        val address: String,
        val region: String,
        val notes: String,
        val portalUrl: String,
        val gsmNumber: String,
        val wifiHost: String,
        val connectionType: ConnectionType,
        val defaultModules: Set<ModuleType>
    )

    val CATALOGUE: List<SeedSite> = listOf(
        SeedSite(
            id = DEMO_ACCOUNT_ID,
            name = DEMO_SITE_NAME,
            address = "Mildura VIC",
            region = "Sunraysia",
            notes = "Main boom gate · Tailscale portal",
            portalUrl = DEMO_PORTAL_URL,
            gsmNumber = DEMO_GSM_NUMBER,
            wifiHost = DEMO_WIFI_HOST,
            connectionType = ConnectionType.HYBRID,
            defaultModules = setOf(ModuleType.WIFI, ModuleType.GSM)
        )
    )

    suspend fun ensureSeeded(database: AppDatabase) {
        // Never seed demo accounts in release builds.
        if (!BuildConfig.DEBUG) return

        val now = System.currentTimeMillis()

        val existingAccount = database.accountDao().getById(DEMO_ACCOUNT_ID)
        val normalisedGsm = when (val r = PhoneNumberNormalizer.normalize(DEMO_GSM_NUMBER)) {
            is PhoneNumberNormalizer.Result.Valid -> r.e164
            else -> DEMO_GSM_NUMBER // keep raw; open-gate will reject until fixed
        }
        if (existingAccount == null) {
            database.accountDao().upsert(
                AccountEntity(
                    id = DEMO_ACCOUNT_ID,
                    siteName = "",
                    portalBaseUrl = DEMO_PORTAL_URL,
                    gsmNumber = normalisedGsm,
                    wifiHost = DEMO_WIFI_HOST,
                    openPath = "/gate/open",
                    address = "",
                    region = "",
                    notes = "",
                    connectionType = ConnectionType.HYBRID.name,
                    onboardingComplete = false,
                    gsmAllowAnyCaller = false,
                    enabled = true,
                    timezone = "Australia/Melbourne",
                    whitelistVersion = 0L,
                    createdAt = now
                )
            )
        } else {
            database.accountDao().upsert(
                existingAccount.copy(
                    portalBaseUrl = DEMO_PORTAL_URL,
                    wifiHost = DEMO_WIFI_HOST,
                    openPath = "/gate/open",
                    // Only overwrite GSM if currently blank; never invent a valid number
                    gsmNumber = existingAccount.gsmNumber.ifBlank { normalisedGsm }
                )
            )
        }

        if (database.moduleDao().list(DEMO_ACCOUNT_ID).isEmpty()) {
            database.moduleDao().upsertAll(
                ModuleType.entries.map {
                    AccountModuleEntity(DEMO_ACCOUNT_ID, it.name, enabled = false)
                }
            )
        }

        database.gsmWhitelistMetaDao().upsert(
            database.gsmWhitelistMetaDao().get(DEMO_ACCOUNT_ID)
                ?: GsmWhitelistMetaEntity(accountId = DEMO_ACCOUNT_ID)
        )

        val existing = database.userDao().getByEmail(DEMO_EMAIL)
        val ownerId = existing?.id ?: UUID.randomUUID().toString()
        database.userDao().upsert(
            UserEntity(
                id = ownerId,
                accountId = DEMO_ACCOUNT_ID,
                email = DEMO_EMAIL,
                passwordHash = PasswordHasher.hash(DEMO_PASSWORD),
                displayName = existing?.displayName ?: "Property Owner",
                role = UserRole.OWNER.name,
                enabled = true,
                mustChangePassword = false,
                modulesJson = "[]"
            )
        )
        database.userSiteDao().removeAllForUser(ownerId)
        database.userSiteDao().upsert(UserSiteEntity(ownerId, DEMO_ACCOUNT_ID))

        // ── Commercial body-corporate site + full contact-sheet whitelist ──
        seedCommercialBodyCorporate(database, ownerId, now)

        if (existing == null) {
            database.actionLogDao().insert(
                ActionLogEntity(
                    accountId = DEMO_ACCOUNT_ID,
                    userId = "system",
                    userEmail = "system",
                    action = "SEED",
                    detail = "Debug demo $DEMO_EMAIL · portal $DEMO_PORTAL_URL (onboarding pending)",
                    success = true,
                    timestamp = now
                )
            )
        }
    }

    /**
     * Import multi-unit commercial contact sheet as ESP32 authorised callers.
     * Demo login is linked to this site so RJL can manage the full list in-app.
     */
    private suspend fun seedCommercialBodyCorporate(
        database: AppDatabase,
        ownerId: String,
        now: Long
    ) {
        val id = CommercialWhitelistSeed.ACCOUNT_ID
        val gateSim = when (val r = PhoneNumberNormalizer.normalize(CommercialWhitelistSeed.GATE_SIM)) {
            is PhoneNumberNormalizer.Result.Valid -> r.e164
            else -> CommercialWhitelistSeed.GATE_SIM
        }
        val existing = database.accountDao().getById(id)
        database.accountDao().upsert(
            AccountEntity(
                id = id,
                siteName = CommercialWhitelistSeed.SITE_NAME,
                portalBaseUrl = "",
                gsmNumber = gateSim,
                wifiHost = "",
                openPath = "/gate/open",
                address = CommercialWhitelistSeed.SITE_ADDRESS,
                region = "Victoria",
                notes = "${CommercialWhitelistSeed.GATE_TYPE} · Commercial GSM — call gate SIM; no client app",
                connectionType = ConnectionType.GSM.name,
                onboardingComplete = true,
                gsmAllowAnyCaller = false,
                enabled = true,
                timezone = "Australia/Melbourne",
                whitelistVersion = existing?.whitelistVersion ?: 1L,
                createdAt = existing?.createdAt ?: now
            )
        )
        database.moduleDao().upsertAll(
            ModuleType.entries.map {
                AccountModuleEntity(
                    accountId = id,
                    moduleType = it.name,
                    enabled = it == ModuleType.GSM
                )
            }
        )
        database.gsmWhitelistMetaDao().upsert(
            database.gsmWhitelistMetaDao().get(id)
                ?: GsmWhitelistMetaEntity(accountId = id, version = 1L)
        )

        // Always refresh whitelist from contact sheet in debug (replace set)
        val callers = CommercialWhitelistSeed.buildEntities(id, now)
        if (callers.isNotEmpty()) {
            // Remove previous commercial seed callers for this account, re-insert
            database.gsmCallerDao().deleteAllForAccount(id)
            database.gsmCallerDao().upsertAll(callers)
        }

        // Point demo owner at commercial site so list is visible after login
        val owner = database.userDao().getById(ownerId)
        if (owner != null) {
            database.userDao().upsert(owner.copy(accountId = id))
            database.userSiteDao().removeAllForUser(ownerId)
            database.userSiteDao().upsert(UserSiteEntity(ownerId, id))
        }
    }
}
