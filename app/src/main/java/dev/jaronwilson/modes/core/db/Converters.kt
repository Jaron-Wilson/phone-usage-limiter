package dev.jaronwilson.modes.core.db

import androidx.room.TypeConverter
import dev.jaronwilson.modes.core.model.EventKind
import dev.jaronwilson.modes.core.model.GuardMode
import dev.jaronwilson.modes.core.model.GuardScope
import dev.jaronwilson.modes.core.model.MatchField
import dev.jaronwilson.modes.core.model.NotifClass

/**
 * Collections are stored as newline-joined text. Package names, regexes and
 * enum names cannot contain a newline, so this round-trips safely and stays
 * readable if you ever open the database by hand.
 */
class Converters {
    private val sep = "\n"

    @TypeConverter
    fun stringSetToDb(value: Set<String>): String = value.joinToString(sep)

    @TypeConverter
    fun dbToStringSet(value: String): Set<String> =
        if (value.isEmpty()) emptySet() else value.split(sep).toSet()

    @TypeConverter
    fun stringListToDb(value: List<String>): String = value.joinToString(sep)

    @TypeConverter
    fun dbToStringList(value: String): List<String> =
        if (value.isEmpty()) emptyList() else value.split(sep)

    @TypeConverter
    fun intListToDb(value: List<Int>): String = value.joinToString(",")

    @TypeConverter
    fun dbToIntList(value: String): List<Int> =
        if (value.isEmpty()) emptyList() else value.split(",").mapNotNull { it.trim().toIntOrNull() }

    @TypeConverter
    fun classSetToDb(value: Set<NotifClass>): String = value.joinToString(",") { it.name }

    @TypeConverter
    fun dbToClassSet(value: String): Set<NotifClass> =
        if (value.isEmpty()) emptySet()
        else value.split(",").mapNotNull { runCatching { NotifClass.valueOf(it) }.getOrNull() }.toSet()

    @TypeConverter
    fun classToDb(value: NotifClass): String = value.name

    @TypeConverter
    fun dbToClass(value: String): NotifClass =
        runCatching { NotifClass.valueOf(value) }.getOrDefault(NotifClass.OTHER)

    @TypeConverter
    fun guardToDb(value: GuardMode): String = value.name

    @TypeConverter
    fun dbToGuard(value: String): GuardMode =
        runCatching { GuardMode.valueOf(value) }.getOrDefault(GuardMode.OFF)

    @TypeConverter
    fun scopeToDb(value: GuardScope): String = value.name

    @TypeConverter
    fun dbToScope(value: String): GuardScope =
        runCatching { GuardScope.valueOf(value) }.getOrDefault(GuardScope.BLOCKLIST)

    @TypeConverter
    fun fieldToDb(value: MatchField): String = value.name

    @TypeConverter
    fun dbToField(value: String): MatchField =
        runCatching { MatchField.valueOf(value) }.getOrDefault(MatchField.ANY)

    @TypeConverter
    fun kindToDb(value: EventKind): String = value.name

    @TypeConverter
    fun dbToKind(value: String): EventKind =
        runCatching { EventKind.valueOf(value) }.getOrDefault(EventKind.APP_OPENED)
}
