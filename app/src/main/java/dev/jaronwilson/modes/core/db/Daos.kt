package dev.jaronwilson.modes.core.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import dev.jaronwilson.modes.core.model.AppPass
import dev.jaronwilson.modes.core.model.CalendarRule
import dev.jaronwilson.modes.core.model.HeldNotification
import dev.jaronwilson.modes.core.model.Mode
import dev.jaronwilson.modes.core.model.NotifRule
import dev.jaronwilson.modes.core.model.TimeRule
import dev.jaronwilson.modes.core.model.Vip
import kotlinx.coroutines.flow.Flow

@Dao
interface ModeDao {
    @Query("SELECT * FROM modes ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<Mode>>

    @Query("SELECT * FROM modes ORDER BY sortOrder, name")
    suspend fun getAll(): List<Mode>

    @Query("SELECT * FROM modes WHERE id = :id")
    suspend fun get(id: String): Mode?

    @Query("SELECT * FROM modes WHERE id = :id")
    fun observe(id: String): Flow<Mode?>

    @Query("SELECT * FROM modes WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefault(): Mode?

    @Upsert
    suspend fun upsert(mode: Mode)

    @Upsert
    suspend fun upsertAll(modes: List<Mode>)

    @Delete
    suspend fun delete(mode: Mode)

    @Query("SELECT COUNT(*) FROM modes")
    suspend fun count(): Int
}

@Dao
interface RuleDao {
    @Query("SELECT * FROM calendar_rules ORDER BY priority DESC, id")
    fun observeCalendarRules(): Flow<List<CalendarRule>>

    @Query("SELECT * FROM calendar_rules WHERE enabled = 1 ORDER BY priority DESC, id")
    suspend fun activeCalendarRules(): List<CalendarRule>

    @Upsert
    suspend fun upsert(rule: CalendarRule)

    @Delete
    suspend fun delete(rule: CalendarRule)

    @Query("SELECT * FROM time_rules ORDER BY priority DESC, startMinute")
    fun observeTimeRules(): Flow<List<TimeRule>>

    @Query("SELECT * FROM time_rules WHERE enabled = 1 ORDER BY priority DESC, startMinute")
    suspend fun activeTimeRules(): List<TimeRule>

    @Upsert
    suspend fun upsert(rule: TimeRule)

    @Delete
    suspend fun delete(rule: TimeRule)

    @Query("SELECT * FROM notif_rules ORDER BY priority DESC, id")
    fun observeNotifRules(): Flow<List<NotifRule>>

    @Query("SELECT * FROM notif_rules WHERE enabled = 1 ORDER BY priority DESC, id")
    suspend fun activeNotifRules(): List<NotifRule>

    @Upsert
    suspend fun upsert(rule: NotifRule)

    @Delete
    suspend fun delete(rule: NotifRule)

    @Query("SELECT * FROM vips ORDER BY pattern")
    fun observeVips(): Flow<List<Vip>>

    @Query("SELECT * FROM vips WHERE enabled = 1")
    suspend fun activeVips(): List<Vip>

    @Upsert
    suspend fun upsert(vip: Vip)

    @Delete
    suspend fun delete(vip: Vip)

    @Query("SELECT COUNT(*) FROM notif_rules")
    suspend fun notifRuleCount(): Int

    @Query("SELECT COUNT(*) FROM time_rules")
    suspend fun timeRuleCount(): Int

    @Query("SELECT COUNT(*) FROM calendar_rules")
    suspend fun calendarRuleCount(): Int
}

@Dao
interface HeldDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(held: HeldNotification)

    @Query("SELECT * FROM held WHERE releasedAt IS NULL ORDER BY postedAt DESC")
    fun observePending(): Flow<List<HeldNotification>>

    @Query("SELECT * FROM held WHERE releasedAt IS NULL ORDER BY postedAt DESC")
    suspend fun pending(): List<HeldNotification>

    @Query("SELECT COUNT(*) FROM held WHERE releasedAt IS NULL")
    fun observePendingCount(): Flow<Int>

    @Query("UPDATE held SET releasedAt = :now WHERE releasedAt IS NULL")
    suspend fun releaseAll(now: Long)

    @Query("UPDATE held SET releasedAt = :now WHERE key = :key")
    suspend fun release(key: String, now: Long)

    @Query("SELECT * FROM held WHERE postedAt > :since ORDER BY postedAt DESC LIMIT 500")
    fun observeHistory(since: Long): Flow<List<HeldNotification>>

    @Query("DELETE FROM held WHERE postedAt < :cutoff")
    suspend fun prune(cutoff: Long)

    @Query("DELETE FROM held WHERE key = :key")
    suspend fun deleteByKey(key: String)
}

@Dao
interface PassDao {
    @Upsert
    suspend fun upsert(pass: AppPass)

    @Query("SELECT * FROM passes WHERE expiresAt > :now")
    suspend fun active(now: Long): List<AppPass>

    @Query("SELECT * FROM passes WHERE packageName = :pkg AND expiresAt > :now LIMIT 1")
    suspend fun activeFor(pkg: String, now: Long): AppPass?

    @Query("DELETE FROM passes WHERE expiresAt <= :now")
    suspend fun prune(now: Long)

    @Query("DELETE FROM passes")
    suspend fun clear()
}
