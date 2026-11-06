package de.gurum.besseresser

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString

// ----------------------------------------------------------------- Room Entities

@Entity(tableName = "history_entries")
data class HistoryEntryEntity(
    @PrimaryKey
    val id: Long,
    val dateMillis: Long,
    val photoFile: String,
    val goalResults: List<GoalResult>,
    val summary: String,
    val note: String,
    val erkannt: String,
    val backdated: Boolean
) {
    fun toDomain(): HistoryEntry = HistoryEntry(
        id = id,
        dateMillis = dateMillis,
        photoFile = photoFile,
        goalResults = goalResults,
        summary = summary,
        note = note,
        erkannt = erkannt,
        backdated = backdated
    )
}

fun HistoryEntry.toEntity(): HistoryEntryEntity = HistoryEntryEntity(
    id = id,
    dateMillis = dateMillis,
    photoFile = photoFile,
    goalResults = goalResults,
    summary = summary,
    note = note,
    erkannt = erkannt,
    backdated = backdated
)

@Entity(tableName = "day_fazits")
data class DayFazitEntity(
    @PrimaryKey
    val dayKey: String,
    val ampel: TrafficLight,
    val text: String,
    val entryIds: List<Long>,
    val createdAt: Long,
    val fingerprint: String
) {
    fun toDomain(): DayFazit = DayFazit(
        dayKey = dayKey,
        ampel = ampel,
        text = text,
        entryIds = entryIds,
        createdAt = createdAt,
        fingerprint = fingerprint
    )
}

fun DayFazit.toEntity(): DayFazitEntity = DayFazitEntity(
    dayKey = dayKey,
    ampel = ampel,
    text = text,
    entryIds = entryIds,
    createdAt = createdAt,
    fingerprint = fingerprint
)

@Entity(tableName = "week_fazits")
data class WeekFazitEntity(
    @PrimaryKey
    val weekKey: String,
    val ampel: TrafficLight,
    val text: String,
    val entryIds: List<Long>,
    val createdAt: Long,
    val fingerprint: String
) {
    fun toDomain(): DayFazit = DayFazit(
        dayKey = weekKey,
        ampel = ampel,
        text = text,
        entryIds = entryIds,
        createdAt = createdAt,
        fingerprint = fingerprint
    )
}

fun DayFazit.toWeekEntity(): WeekFazitEntity = WeekFazitEntity(
    weekKey = dayKey,
    ampel = ampel,
    text = text,
    entryIds = entryIds,
    createdAt = createdAt,
    fingerprint = fingerprint
)

// ----------------------------------------------------------------- Type Converter

class RoomConverters {
    @TypeConverter
    fun fromGoalResultList(value: List<GoalResult>): String =
        AppJson.encodeToString(value)

    @TypeConverter
    fun toGoalResultList(value: String): List<GoalResult> =
        runCatching { AppJson.decodeFromString<List<GoalResult>>(value) }.getOrDefault(emptyList())

    @TypeConverter
    fun fromLongList(value: List<Long>): String =
        AppJson.encodeToString(value)

    @TypeConverter
    fun toLongList(value: String): List<Long> =
        runCatching { AppJson.decodeFromString<List<Long>>(value) }.getOrDefault(emptyList())

    @TypeConverter
    fun fromTrafficLight(value: TrafficLight): String =
        value.rawValue

    @TypeConverter
    fun toTrafficLight(value: String): TrafficLight =
        TrafficLight.fromString(value)
}

// ----------------------------------------------------------------- DAOs

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history_entries ORDER BY dateMillis DESC")
    fun getAllFlow(): Flow<List<HistoryEntryEntity>>

    @Query("SELECT * FROM history_entries ORDER BY dateMillis DESC")
    suspend fun getAll(): List<HistoryEntryEntity>

    @Query("SELECT * FROM history_entries WHERE id = :id")
    suspend fun getById(id: Long): HistoryEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: HistoryEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<HistoryEntryEntity>)

    @Query("DELETE FROM history_entries WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE history_entries SET erkannt = :erkannt WHERE id = :id")
    suspend fun updateErkannt(id: Long, erkannt: String)

    @Query("UPDATE history_entries SET goalResults = :goalResults, summary = :summary WHERE id = :id")
    suspend fun updateEvaluation(id: Long, goalResults: List<GoalResult>, summary: String)
}

@Dao
interface DayFazitDao {
    @Query("SELECT * FROM day_fazits")
    fun getAllFlow(): Flow<List<DayFazitEntity>>

    @Query("SELECT * FROM day_fazits")
    suspend fun getAll(): List<DayFazitEntity>

    @Query("SELECT * FROM day_fazits WHERE dayKey = :dayKey")
    suspend fun getByKey(dayKey: String): DayFazitEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DayFazitEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<DayFazitEntity>)

    @Query("DELETE FROM day_fazits WHERE dayKey = :dayKey")
    suspend fun delete(dayKey: String)
}

@Dao
interface WeekFazitDao {
    @Query("SELECT * FROM week_fazits")
    fun getAllFlow(): Flow<List<WeekFazitEntity>>

    @Query("SELECT * FROM week_fazits WHERE weekKey = :weekKey")
    suspend fun getByKey(weekKey: String): WeekFazitEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: WeekFazitEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<WeekFazitEntity>)

    @Query("DELETE FROM week_fazits WHERE weekKey = :weekKey")
    suspend fun delete(weekKey: String)
}

// ----------------------------------------------------------------- Database

@Database(
    entities = [HistoryEntryEntity::class, DayFazitEntity::class, WeekFazitEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(RoomConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun dayFazitDao(): DayFazitDao
    abstract fun weekFazitDao(): WeekFazitDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "besseresser.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
