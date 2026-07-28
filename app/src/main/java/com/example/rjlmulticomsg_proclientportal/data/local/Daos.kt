package com.example.rjlmulticomsg_proclientportal.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AccountEntity?

    @Query("SELECT * FROM accounts ORDER BY siteName ASC")
    suspend fun listAll(): List<AccountEntity>

    @Query("SELECT * FROM accounts ORDER BY siteName ASC")
    fun observeAll(): Flow<List<AccountEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: AccountEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(accounts: List<AccountEntity>)

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun count(): Int
}

@Dao
interface UserDao {
    @Query("SELECT * FROM users WHERE LOWER(email) = LOWER(:email) LIMIT 1")
    suspend fun getByEmail(email: String): UserEntity?

    @Query("SELECT * FROM users WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): UserEntity?

    @Query("SELECT * FROM users WHERE accountId = :accountId ORDER BY role ASC, displayName ASC")
    fun observeByAccount(accountId: String): Flow<List<UserEntity>>

    @Query("SELECT * FROM users WHERE accountId = :accountId ORDER BY role ASC, displayName ASC")
    suspend fun listByAccount(accountId: String): List<UserEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(user: UserEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(user: UserEntity)

    @Update
    suspend fun update(user: UserEntity)

    @Query("DELETE FROM users WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM users")
    suspend fun count(): Int
}

@Dao
interface UserSiteDao {
    @Query("SELECT accountId FROM user_sites WHERE userId = :userId")
    suspend fun accountIdsForUser(userId: String): List<String>

    @Query(
        """
        SELECT a.* FROM accounts a
        INNER JOIN user_sites us ON us.accountId = a.id
        WHERE us.userId = :userId
        ORDER BY a.siteName ASC
        """
    )
    suspend fun sitesForUser(userId: String): List<AccountEntity>

    @Query(
        """
        SELECT a.* FROM accounts a
        INNER JOIN user_sites us ON us.accountId = a.id
        WHERE us.userId = :userId
        ORDER BY a.siteName ASC
        """
    )
    fun observeSitesForUser(userId: String): Flow<List<AccountEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(link: UserSiteEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(links: List<UserSiteEntity>)

    @Query("DELETE FROM user_sites WHERE userId = :userId AND accountId = :accountId")
    suspend fun remove(userId: String, accountId: String)

    @Query("DELETE FROM user_sites WHERE userId = :userId")
    suspend fun removeAllForUser(userId: String)
}

@Dao
interface ModuleDao {
    @Query("SELECT * FROM account_modules WHERE accountId = :accountId")
    fun observe(accountId: String): Flow<List<AccountModuleEntity>>

    @Query("SELECT * FROM account_modules WHERE accountId = :accountId")
    suspend fun list(accountId: String): List<AccountModuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AccountModuleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<AccountModuleEntity>)

    @Query("SELECT COUNT(*) FROM account_modules WHERE accountId = :accountId AND enabled = 1")
    suspend fun enabledCount(accountId: String): Int
}

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedules WHERE accountId = :accountId ORDER BY name ASC")
    fun observe(accountId: String): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules WHERE accountId = :accountId ORDER BY name ASC")
    suspend fun getByAccountId(accountId: String): List<ScheduleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ScheduleEntity): Long

    @Query("DELETE FROM schedules WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE schedules SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)
}

@Dao
interface ActionLogDao {
    @Query("SELECT * FROM action_logs WHERE accountId = :accountId ORDER BY timestamp DESC LIMIT :limit")
    fun observe(accountId: String, limit: Int = 200): Flow<List<ActionLogEntity>>

    @Insert
    suspend fun insert(entity: ActionLogEntity): Long
}

@Dao
interface RfidDao {
    @Query("SELECT * FROM rfid_tags WHERE accountId = :accountId ORDER BY label ASC")
    fun observe(accountId: String): Flow<List<RfidTagEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RfidTagEntity): Long

    @Query("DELETE FROM rfid_tags WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface LprDao {
    @Query("SELECT * FROM lpr_plates WHERE accountId = :accountId ORDER BY label ASC")
    fun observe(accountId: String): Flow<List<LprPlateEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LprPlateEntity): Long

    @Query("DELETE FROM lpr_plates WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface GsmCallerDao {
    @Query("SELECT * FROM gsm_callers WHERE accountId = :accountId ORDER BY displayName ASC")
    fun observe(accountId: String): Flow<List<GsmCallerEntity>>

    @Query("SELECT * FROM gsm_callers WHERE accountId = :accountId ORDER BY displayName ASC")
    suspend fun list(accountId: String): List<GsmCallerEntity>

    @Query("SELECT * FROM gsm_callers WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): GsmCallerEntity?

    @Query(
        """
        SELECT * FROM gsm_callers
        WHERE accountId = :accountId AND phoneNumberE164 = :e164
        LIMIT 1
        """
    )
    suspend fun getByPhone(accountId: String, e164: String): GsmCallerEntity?

    @Query(
        """
        SELECT * FROM gsm_callers
        WHERE accountId = :accountId AND linkedUserId = :userId
        LIMIT 1
        """
    )
    suspend fun getByLinkedUser(accountId: String, userId: String): GsmCallerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: GsmCallerEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<GsmCallerEntity>)

    @Query("DELETE FROM gsm_callers WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM gsm_callers WHERE accountId = :accountId")
    suspend fun deleteAllForAccount(accountId: String)

    @Query("SELECT * FROM gsm_callers WHERE pendingSync = 1")
    suspend fun listPendingSync(): List<GsmCallerEntity>
}

@Dao
interface GsmDeviceDao {
    @Query("SELECT * FROM gsm_devices WHERE accountId = :accountId ORDER BY deviceName ASC")
    fun observe(accountId: String): Flow<List<GsmDeviceEntity>>

    @Query("SELECT * FROM gsm_devices WHERE accountId = :accountId ORDER BY deviceName ASC")
    suspend fun list(accountId: String): List<GsmDeviceEntity>

    @Query("SELECT * FROM gsm_devices WHERE accountId = :accountId ORDER BY deviceName ASC")
    suspend fun listByAccount(accountId: String): List<GsmDeviceEntity>

    @Query("SELECT * FROM gsm_devices WHERE deviceId = :deviceId LIMIT 1")
    suspend fun getById(deviceId: String): GsmDeviceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: GsmDeviceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<GsmDeviceEntity>)
}

@Dao
interface GsmCallLogDao {
    @Query(
        """
        SELECT * FROM gsm_call_logs
        WHERE accountId = :accountId
        ORDER BY receivedAt DESC
        LIMIT :limit
        """
    )
    fun observe(accountId: String, limit: Int = 100): Flow<List<GsmCallLogEntity>>

    @Query(
        """
        SELECT * FROM gsm_call_logs
        WHERE accountId = :accountId
        ORDER BY receivedAt DESC
        LIMIT :limit
        """
    )
    suspend fun list(accountId: String, limit: Int = 100): List<GsmCallLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: GsmCallLogEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<GsmCallLogEntity>)
}

@Dao
interface GsmWhitelistMetaDao {
    @Query("SELECT * FROM gsm_whitelist_meta WHERE accountId = :accountId LIMIT 1")
    suspend fun get(accountId: String): GsmWhitelistMetaEntity?

    @Query("SELECT * FROM gsm_whitelist_meta WHERE accountId = :accountId LIMIT 1")
    fun observe(accountId: String): Flow<GsmWhitelistMetaEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: GsmWhitelistMetaEntity)
}
