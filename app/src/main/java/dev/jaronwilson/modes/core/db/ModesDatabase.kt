package dev.jaronwilson.modes.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.TypeConverters
import dev.jaronwilson.modes.core.model.AppPass
import dev.jaronwilson.modes.core.model.Place
import dev.jaronwilson.modes.core.model.CalendarRule
import dev.jaronwilson.modes.core.model.Folder
import dev.jaronwilson.modes.core.model.HeldNotification
import dev.jaronwilson.modes.core.model.HomeEntry
import dev.jaronwilson.modes.core.model.Mode
import dev.jaronwilson.modes.core.model.NotifRule
import dev.jaronwilson.modes.core.model.TimeRule
import dev.jaronwilson.modes.core.model.UsageEvent
import dev.jaronwilson.modes.core.model.Vip

@Database(
    entities = [
        Mode::class,
        CalendarRule::class,
        TimeRule::class,
        NotifRule::class,
        Vip::class,
        HeldNotification::class,
        HomeEntry::class,
        Folder::class,
        UsageEvent::class,
        AppPass::class,
        Place::class
    ],
    version = 8,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class ModesDatabase : RoomDatabase() {
    abstract fun modeDao(): ModeDao
    abstract fun ruleDao(): RuleDao
    abstract fun heldDao(): HeldDao
    abstract fun homeDao(): HomeDao
    abstract fun folderDao(): FolderDao
    abstract fun eventDao(): EventDao
    abstract fun passDao(): PassDao
    abstract fun placeDao(): PlaceDao

    companion object {
        /**
         * Folders gained a list of child folders. Nothing existing changes, so
         * this adds the column and leaves every row alone. Destructive
         * migration is fine while a schema is being invented and stops being
         * fine once somebody has arranged their phone.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE folders ADD COLUMN subFolders TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Location-based mode switching arrives with its own table. */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS places (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "enabled INTEGER NOT NULL DEFAULT 1, " +
                        "name TEXT NOT NULL, " +
                        "latitude REAL NOT NULL, " +
                        "longitude REAL NOT NULL, " +
                        "radiusMeters REAL NOT NULL DEFAULT 150.0, " +
                        "modeId TEXT NOT NULL, " +
                        "priority INTEGER NOT NULL DEFAULT 0)"
                )
            }
        }

        @Volatile
        private var instance: ModesDatabase? = null

        fun get(context: Context): ModesDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ModesDatabase::class.java,
                "modes.db"
            )
                .addMigrations(MIGRATION_6_7, MIGRATION_7_8)
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
