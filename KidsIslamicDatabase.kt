package com.example.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "level_progress")
data class LevelProgressEntity(
    @PrimaryKey val levelId: Int,
    val starsEarned: Int = 0,
    val isCompleted: Boolean = false,
    val bestScore: Int = 0,
    val mistakeCategory: String = "NONE",
    val isOfflinePackDownloaded: Boolean = true,
    val isPendingCloudSync: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "letter_mastery")
data class LetterMasteryEntity(
    @PrimaryKey val letterId: Int,
    val practiceCount: Int = 0,
    val isMastered: Boolean = false,
    val isTracingPracticed: Boolean = false,
    val lastPracticedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "kid_profile")
data class KidProfileEntity(
    @PrimaryKey val id: Int = 1,
    val explorerName: String = "ছোট্ট অভিযাত্রী",
    val avatarIndex: Int = 0,
    val lastActiveLevelId: Int = 1,
    val lastActiveTab: String = "HOME",
    val streakDays: Int = 1,
    val bonusGameStars: Int = 0,
    val dailyGoalMinutes: Int = 15,
    val dailyPracticedMinutes: Int = 5,
    val hasCompletedOnboarding: Boolean = false,
    val audioVolumePercent: Int = 90,
    val offlineAudioDownloaded: Boolean = true,
    val simulateOfflineMode: Boolean = false,
    val lastSyncedAt: Long = 0L
)

@Dao
interface KidsIslamicDao {
    @Query("SELECT * FROM level_progress ORDER BY levelId ASC")
    fun getAllLevelProgress(): Flow<List<LevelProgressEntity>>

    @Query("SELECT * FROM level_progress ORDER BY levelId ASC")
    suspend fun getAllLevelProgressList(): List<LevelProgressEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLevelProgress(progress: LevelProgressEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAllLevelProgress(progressList: List<LevelProgressEntity>)

    @Query("SELECT * FROM letter_mastery ORDER BY letterId ASC")
    fun getAllLetterMastery(): Flow<List<LetterMasteryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLetterMastery(mastery: LetterMasteryEntity)

    @Query("SELECT * FROM kid_profile WHERE id = 1 LIMIT 1")
    fun getKidProfile(): Flow<KidProfileEntity?>

    @Query("SELECT * FROM kid_profile WHERE id = 1 LIMIT 1")
    suspend fun getKidProfileOnce(): KidProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertKidProfile(profile: KidProfileEntity)
}

@Database(
    entities = [
        LevelProgressEntity::class,
        LetterMasteryEntity::class,
        KidProfileEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class KidsIslamicDatabase : RoomDatabase() {
    abstract fun dao(): KidsIslamicDao

    companion object {
        @Volatile
        private var INSTANCE: KidsIslamicDatabase? = null

        fun getDatabase(context: Context): KidsIslamicDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    KidsIslamicDatabase::class.java,
                    "kids_islamic_adventure.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
