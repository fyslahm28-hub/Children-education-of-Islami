package com.example.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import kotlin.math.abs

@IgnoreExtraProperties
data class FirestoreUserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val explorerName: String = "ছোট্ট অভিযাত্রী",
    val avatarIndex: Long = 0L,
    val totalStars: Long = 0L,
    val completedLevelsCount: Long = 0L,
    val lastActiveLevelId: Long = 1L,
    val dailyGoalMinutes: Long = 15L,
    val dailyPracticedMinutes: Long = 5L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class FirestoreLessonProgress(
    @DocumentId val id: String = "",
    val userId: String = "",
    val levelId: Long = 1L,
    val starsEarned: Long = 0L,
    val bestScore: Long = 0L,
    val isCompleted: Boolean = false,
    val mistakeCategory: String = "NONE",
    val clientUpdatedAtEpoch: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class FirestoreCurriculumLesson(
    @DocumentId val id: String = "",
    val stageNumber: Long = 1L,
    val levelOrder: Long = 1L,
    val titleBn: String = "",
    val arabicContent: String = "",
    val pronunciationBn: String = "",
    val meaningBn: String = "",
    val audioSourceUrl: String = "",
    val teacherVerified: Boolean = false,
    val isPublished: Boolean = false,
    val authorUid: String = "",
    val quizQuestionBn: String = "",
    val quizOptionsJoined: String = "",
    val quizCorrectIndex: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    /**
     * Converts a dynamic Cloud Firestore lesson into an interactive [AdventureLevel]
     * so learners can practice its flashcard, listen to its verified audio, and solve its quiz.
     */
    fun toPlayableAdventureLevel(): AdventureLevel {
        val stageCat = StageCategory.entries.find { it.stageNumber == stageNumber.toInt() }
            ?: StageCategory.STAGE_1_FRIENDS_OF_LETTERS
        val parsedOptions = quizOptionsJoined
            .split("|")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .let { list ->
                if (list.size >= 2) list else listOf(
                    "$arabicContent (${pronunciationBn.ifBlank { "সঠিক উচ্চারণ" }})",
                    "بَ (বা)",
                    "تُ (তু)"
                )
            }
        val safeCorrectIndex = quizCorrectIndex.toInt().coerceIn(0, parsedOptions.lastIndex)
        val effectiveQuestion = quizQuestionBn.ifBlank {
            "'$arabicContent'-এর সঠিক উচ্চারণ বা অর্থ কোনটি?"
        }
        val stableId = (100 + abs(id.hashCode() % 350)).coerceIn(100, 499)

        return AdventureLevel(
            id = stableId,
            levelNumber = levelOrder.toInt().coerceAtLeast(13),
            titleBn = titleBn.ifBlank { "ক্লাউড অনুশীলন পাঠ" },
            storyIntroBn = "চলো শিক্ষকের যুক্ত করা নতুন পাঠ '$titleBn' মনোযোগ দিয়ে শুনি ও কুইজ জিতি!",
            category = stageCat,
            badgeTitleBn = "${titleBn.take(20)} বিজয়ী",
            teacherVerified = teacherVerified,
            items = listOf(
                LessonItemCard(
                    id = "${id}_card_1",
                    arabicText = arabicContent.ifBlank { "بِسْمِ اللَّهِ" },
                    banglaPronunciation = pronunciationBn.ifBlank { "বিসমিল্লাহি" },
                    meaningOrTipBn = meaningBn.ifBlank { "আল্লাহর নামে শুরু" },
                    audioTextAr = arabicContent.ifBlank { "بِسْمِ اللَّهِ" },
                    highlightRuleBn = "যোগ্য কোরআন শিক্ষক কর্তৃক যাচাইকৃত পাঠ ও উচ্চারণ",
                    verifiedAudioUrl = audioSourceUrl
                )
            ),
            challenges = listOf(
                QuizChallengeItem(
                    questionBn = effectiveQuestion,
                    targetArabicAudio = arabicContent.ifBlank { "بِسْمِ اللَّهِ" },
                    displayPromptAr = arabicContent.ifBlank { "بِسْمِ اللَّهِ" },
                    options = parsedOptions,
                    correctOptionIndex = safeCorrectIndex,
                    explanationBn = "'$arabicContent'-এর সঠিক উচ্চারণ হলো '$pronunciationBn' এবং অর্থ: $meaningBn।",
                    mistakeCategoryTag = "ক্লাউড পাঠ: $titleBn"
                )
            )
        )
    }
}

@IgnoreExtraProperties
data class FirestoreAdminAuditLog(
    @DocumentId val id: String = "",
    val adminUid: String = "",
    val actionType: String = "",
    val targetId: String = "",
    val summaryBn: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)
