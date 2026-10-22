package com.example.data

import android.content.Context
import com.example.R
import com.example.util.OperationType
import com.example.util.handleFirestoreError
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID

class CloudIslamicRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    companion object {
        const val PROJECT_OWNER_EMAIL = "foysalahamad102@gmail.com"
    }

    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        FirebaseAuth.getInstance()
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    /**
     * Checks if the currently signed-in user is a verified Admin in `/admins/{uid}`
     * or is the verified project owner account (`foysalahamad102@gmail.com`).
     */
    suspend fun checkIsVerifiedAdmin(): Boolean {
        val currentUser = auth.currentUser ?: return false
        val uid = currentUser.uid
        val isOwnerEmail = currentUser.email?.trim()?.lowercase() == PROJECT_OWNER_EMAIL
        val path = "admins/$uid"
        return try {
            val snap = db.collection("admins").document(uid).get().await()
            if (snap.exists()) {
                true
            } else if (isOwnerEmail) {
                // Automatically register the verified project owner in /admins/{uid}
                ensureOwnerAdminRecord()
                true
            } else {
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, path)
            isOwnerEmail
        }
    }

    /**
     * Registers or refreshes the verified project owner's `/admins/{uid}` document in Cloud Firestore.
     */
    suspend fun ensureOwnerAdminRecord(): Result<Unit> {
        val currentUser = auth.currentUser
            ?: return Result.failure(IllegalStateException("Google লগইন সেশন পাওয়া যায়নি।"))
        val uid = currentUser.uid
        val email = currentUser.email?.trim()?.lowercase() ?: ""
        val docRef = db.collection("admins").document(uid)
        return try {
            docRef.set(
                mapOf(
                    "email" to email.ifBlank { PROJECT_OWNER_EMAIL }.take(150),
                    "role" to "SUPER_ADMIN",
                    "grantedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    /**
     * Observes the authenticated user's profile in `/users/{uid}`.
     */
    fun observeUserProfile(userId: String): Flow<FirestoreUserProfile?> {
        val path = "users/$userId"
        return db.collection("users").document(userId)
            .snapshots()
            .map { snapshot ->
                if (snapshot.exists()) {
                    snapshot.toObject(
                        FirestoreUserProfile::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                } else {
                    null
                }
            }
            .catch { error ->
                if (error is Exception) handleFirestoreError(error, OperationType.GET, path)
                throw error
            }
    }

    /**
     * Observes all lesson progress records for the authenticated user.
     */
    fun observeUserLessonProgress(userId: String): Flow<List<FirestoreLessonProgress>> {
        val path = "users/$userId/lesson_progress"
        return db.collection("users").document(userId).collection("lesson_progress")
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    FirestoreLessonProgress::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }
            .catch { error ->
                if (error is Exception) handleFirestoreError(error, OperationType.LIST, path)
                throw error
            }
    }

    /**
     * Deterministic, non-duplicating, anti-regression sync for a single lesson's progress.
     * Uses document ID `lesson_{levelId}` so multiple offline-to-online sync attempts
     * never create duplicate rows, and merges max(starsEarned), max(bestScore), and max(clientUpdatedAtEpoch)
     * so older offline data never overwrites newer progress.
     */
    suspend fun syncSingleLessonProgress(
        levelId: Int,
        localStars: Int,
        localBestScore: Int,
        localCompleted: Boolean,
        mistakeCategory: String,
        localUpdatedAtEpoch: Long
    ): Result<FirestoreLessonProgress> {
        val uid = requireUserId()
        val docId = "lesson_$levelId"
        val docRef = db.collection("users").document(uid).collection("lesson_progress").document(docId)

        return try {
            val existingSnap = docRef.get().await()
            if (!existingSnap.exists()) {
                val createPayload = mapOf(
                    "userId" to uid,
                    "levelId" to levelId.toLong(),
                    "starsEarned" to localStars.coerceIn(0, 3).toLong(),
                    "bestScore" to localBestScore.coerceAtLeast(0).toLong(),
                    "isCompleted" to localCompleted,
                    "mistakeCategory" to mistakeCategory.take(80),
                    "clientUpdatedAtEpoch" to localUpdatedAtEpoch.coerceAtLeast(1L),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(createPayload).await()
            } else {
                val remoteStars = (existingSnap.getLong("starsEarned") ?: 0L).toInt()
                val remoteScore = (existingSnap.getLong("bestScore") ?: 0L).toInt()
                val remoteCompleted = existingSnap.getBoolean("isCompleted") ?: false
                val remoteEpoch = existingSnap.getLong("clientUpdatedAtEpoch") ?: 0L
                val remoteMistake = existingSnap.getString("mistakeCategory") ?: "NONE"

                val mergedStars = maxOf(remoteStars, localStars.coerceIn(0, 3))
                val mergedScore = maxOf(remoteScore, localBestScore.coerceAtLeast(0))
                val mergedCompleted = remoteCompleted || localCompleted
                val mergedEpoch = maxOf(remoteEpoch, localUpdatedAtEpoch, System.currentTimeMillis())
                val mergedMistake = if (localUpdatedAtEpoch >= remoteEpoch) mistakeCategory.take(80) else remoteMistake

                val updatePayload = mapOf(
                    "starsEarned" to mergedStars.toLong(),
                    "bestScore" to mergedScore.toLong(),
                    "isCompleted" to mergedCompleted,
                    "mistakeCategory" to mergedMistake,
                    "clientUpdatedAtEpoch" to mergedEpoch,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.update(updatePayload).await()
            }

            val updatedSnap = docRef.get().await()
            val model = updatedSnap.toObject(
                FirestoreLessonProgress::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            ) ?: FirestoreLessonProgress(
                id = docId,
                userId = uid,
                levelId = levelId.toLong(),
                starsEarned = localStars.toLong(),
                bestScore = localBestScore.toLong(),
                isCompleted = localCompleted,
                mistakeCategory = mistakeCategory,
                clientUpdatedAtEpoch = localUpdatedAtEpoch
            )
            Result.success(model)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    /**
     * Upserts the user's cloud profile and parent goal settings safely.
     */
    suspend fun syncUserProfile(
        explorerName: String,
        avatarIndex: Int,
        totalStars: Int,
        completedLevelsCount: Int,
        lastActiveLevelId: Int,
        dailyGoalMinutes: Int,
        dailyPracticedMinutes: Int
    ): Result<Unit> {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid)
        return try {
            val snap = docRef.get().await()
            val safeName = explorerName.trim().ifBlank { "ছোট্ট অভিযাত্রী" }.take(80)
            val safeAvatar = avatarIndex.coerceIn(0, 20)
            val safeGoal = dailyGoalMinutes.coerceIn(5, 180)
            val safePracticed = dailyPracticedMinutes.coerceIn(0, 1440)

            if (!snap.exists()) {
                val payload = mapOf(
                    "userId" to uid,
                    "explorerName" to safeName,
                    "avatarIndex" to safeAvatar.toLong(),
                    "totalStars" to totalStars.coerceAtLeast(0).toLong(),
                    "completedLevelsCount" to completedLevelsCount.coerceAtLeast(0).toLong(),
                    "lastActiveLevelId" to lastActiveLevelId.coerceAtLeast(1).toLong(),
                    "dailyGoalMinutes" to safeGoal.toLong(),
                    "dailyPracticedMinutes" to safePracticed.toLong(),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(payload).await()
            } else {
                val remoteStars = (snap.getLong("totalStars") ?: 0L).toInt()
                val remoteCompleted = (snap.getLong("completedLevelsCount") ?: 0L).toInt()
                val payload = mapOf(
                    "explorerName" to safeName,
                    "avatarIndex" to safeAvatar.toLong(),
                    "totalStars" to maxOf(remoteStars, totalStars).toLong(),
                    "completedLevelsCount" to maxOf(remoteCompleted, completedLevelsCount).toLong(),
                    "lastActiveLevelId" to lastActiveLevelId.coerceAtLeast(1).toLong(),
                    "dailyGoalMinutes" to safeGoal.toLong(),
                    "dailyPracticedMinutes" to safePracticed.toLong(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.update(payload).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    /**
     * Observes published dynamic lessons from `/curriculum_lessons`.
     */
    fun observePublishedLessons(): Flow<List<FirestoreCurriculumLesson>> = flow {
        val path = "curriculum_lessons"
        emitAll(
            db.collection(path)
                .whereEqualTo("isPublished", true)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        FirestoreCurriculumLesson::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    if (error is Exception) handleFirestoreError(error, OperationType.LIST, path)
                    throw error
                }
        )
    }

    /**
     * Admin-only: Observes all curriculum lessons (both draft and published).
     */
    fun observeAllAdminLessons(): Flow<List<FirestoreCurriculumLesson>> = flow {
        val path = "curriculum_lessons"
        emitAll(
            db.collection(path)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        FirestoreCurriculumLesson::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    if (error is Exception) handleFirestoreError(error, OperationType.LIST, path)
                    throw error
                }
        )
    }

    /**
     * Admin-only: Creates or updates a dynamic curriculum lesson (including optional quiz fields)
     * and records an immutable audit log.
     */
    suspend fun saveAdminCurriculumLesson(
        existingId: String?,
        stageNumber: Int,
        levelOrder: Int,
        titleBn: String,
        arabicContent: String,
        pronunciationBn: String,
        meaningBn: String,
        audioSourceUrl: String,
        teacherVerified: Boolean,
        isPublished: Boolean,
        quizQuestionBn: String = "",
        quizOptionsJoined: String = "",
        quizCorrectIndex: Int = 0
    ): Result<String> {
        val uid = requireUserId()
        if (isPublished && !teacherVerified) {
            return Result.failure(
                IllegalArgumentException("যোগ্য কোরআন শিক্ষকের যাচাই (Teacher Verified) ছাড়া কোনো পাঠ প্রকাশ (Publish) করা যাবে না!")
            )
        }

        val docId = existingId?.takeIf { it.isNotBlank() } ?: "lesson_dyn_${UUID.randomUUID().toString().take(8)}"
        val docRef = db.collection("curriculum_lessons").document(docId)

        return try {
            val existingSnap = docRef.get().await()
            val baseMap = mutableMapOf<String, Any>(
                "stageNumber" to stageNumber.coerceIn(1, 6).toLong(),
                "levelOrder" to levelOrder.coerceIn(1, 500).toLong(),
                "titleBn" to titleBn.trim().take(150),
                "arabicContent" to arabicContent.trim().take(2000),
                "pronunciationBn" to pronunciationBn.trim().take(1000),
                "meaningBn" to meaningBn.trim().take(1500),
                "audioSourceUrl" to audioSourceUrl.trim().take(500),
                "teacherVerified" to teacherVerified,
                "isPublished" to isPublished,
                "authorUid" to uid,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (quizQuestionBn.isNotBlank() || quizOptionsJoined.isNotBlank()) {
                baseMap["quizQuestionBn"] = quizQuestionBn.trim().take(500)
                baseMap["quizOptionsJoined"] = quizOptionsJoined.trim().take(1000)
                baseMap["quizCorrectIndex"] = quizCorrectIndex.coerceIn(0, 3).toLong()
            }

            if (!existingSnap.exists()) {
                baseMap["createdAt"] = FieldValue.serverTimestamp()
                docRef.set(baseMap).await()
            } else {
                docRef.update(baseMap).await()
            }

            // Record Admin Audit Log
            val logId = "log_${UUID.randomUUID().toString().take(8)}"
            val logRef = db.collection("admin_audit_logs").document(logId)
            val actionType = if (existingId == null) "CREATE_LESSON" else if (isPublished) "PUBLISH_LESSON" else "UPDATE_LESSON"
            logRef.set(
                mapOf(
                    "adminUid" to uid,
                    "actionType" to actionType,
                    "targetId" to docId,
                    "summaryBn" to "পাঠ '$titleBn' (ধাপ $stageNumber) সংরক্ষিত হয়েছে",
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()

            Result.success(docId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    /**
     * Admin-only: Deletes a dynamic curriculum lesson and records an immutable audit log.
     */
    suspend fun deleteAdminCurriculumLesson(
        lessonId: String,
        titleBn: String
    ): Result<Unit> {
        val uid = requireUserId()
        val docRef = db.collection("curriculum_lessons").document(lessonId)
        return try {
            docRef.delete().await()
            val logId = "log_${UUID.randomUUID().toString().take(8)}"
            db.collection("admin_audit_logs").document(logId).set(
                mapOf(
                    "adminUid" to uid,
                    "actionType" to "DELETE_LESSON",
                    "targetId" to lessonId,
                    "summaryBn" to "পাঠ '$titleBn' ($lessonId) মুছে ফেলা হয়েছে",
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    /**
     * Admin-only: Observes immutable admin audit logs.
     */
    fun observeAdminAuditLogs(): Flow<List<FirestoreAdminAuditLog>> = flow {
        val path = "admin_audit_logs"
        emitAll(
            db.collection(path)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        FirestoreAdminAuditLog::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    if (error is Exception) handleFirestoreError(error, OperationType.LIST, path)
                    throw error
                }
        )
    }
}
