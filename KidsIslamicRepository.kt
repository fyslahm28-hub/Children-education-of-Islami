package com.example.data

import kotlinx.coroutines.flow.Flow

class KidsIslamicRepository(private val dao: KidsIslamicDao) {

    val allLevelProgress: Flow<List<LevelProgressEntity>> = dao.getAllLevelProgress()
    val allLetterMastery: Flow<List<LetterMasteryEntity>> = dao.getAllLetterMastery()
    val kidProfile: Flow<KidProfileEntity?> = dao.getKidProfile()

    suspend fun getAllLevelProgressList(): List<LevelProgressEntity> = dao.getAllLevelProgressList()
    suspend fun getKidProfileOnce(): KidProfileEntity? = dao.getKidProfileOnce()

    suspend fun saveLevelCompletion(
        levelId: Int,
        starsEarned: Int,
        score: Int,
        mistakeCategory: String,
        isPendingCloudSync: Boolean
    ): LevelProgressEntity {
        val existing = dao.getAllLevelProgressList().find { it.levelId == levelId }
        val mergedStars = maxOf(existing?.starsEarned ?: 0, starsEarned.coerceIn(1, 3))
        val mergedScore = maxOf(existing?.bestScore ?: 0, score.coerceAtLeast(0))
        val now = maxOf(existing?.updatedAt?.plus(1L) ?: 0L, System.currentTimeMillis())

        val entity = LevelProgressEntity(
            levelId = levelId,
            starsEarned = mergedStars,
            isCompleted = true,
            bestScore = mergedScore,
            mistakeCategory = mistakeCategory,
            isOfflinePackDownloaded = existing?.isOfflinePackDownloaded ?: true,
            isPendingCloudSync = isPendingCloudSync,
            updatedAt = now
        )
        dao.upsertLevelProgress(entity)
        return entity
    }

    suspend fun upsertLevelProgressDirect(entity: LevelProgressEntity) {
        dao.upsertLevelProgress(entity)
    }

    suspend fun markLetterPracticed(letterId: Int, currentCount: Int, traced: Boolean = false) {
        val newCount = currentCount + 1
        dao.upsertLetterMastery(
            LetterMasteryEntity(
                letterId = letterId,
                practiceCount = newCount,
                isMastered = newCount >= 2 || traced,
                isTracingPracticed = traced,
                lastPracticedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun updateProfile(profile: KidProfileEntity) {
        dao.upsertKidProfile(profile)
    }
}
