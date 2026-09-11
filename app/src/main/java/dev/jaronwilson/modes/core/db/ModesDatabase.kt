package dev.jaronwilson.modes.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.jaronwilson.modes.core.model.AppPass
import dev.jaronwilson.modes.core.model.CalendarRule
import dev.jaronwilson.modes.core.model.HeldNotification
import dev.jaronwilson.modes.core.model.Mode
import dev.jaronwilson.modes.core.model.NotifRule
import dev.jaronwilson.modes.core.model.TimeRule
import dev.jaronwilson.modes.core.model.Vip

@Database(
    entities = [
        Mode::class,
        CalendarRule::class,
        TimeRule::class,
        NotifRule::class,
        Vip::class,
        HeldNotification::class,
        AppPass::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class ModesDatabase : RoomDatabase() {
    abstract fun modeDao(): ModeDao
    abstract fun ruleDao(): RuleDao
    abstract fun heldDao(): HeldDao
    abstract fun passDao(): PassDao

    companion object {
        @Volatile
        private var instance: ModesDatabase? = null

        fun get(context: Context): ModesDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ModesDatabase::class.java,
                "modes.db"
            )
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
