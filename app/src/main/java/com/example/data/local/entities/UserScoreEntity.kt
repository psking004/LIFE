package com.example.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.domain.model.UserScore

@Entity(tableName = "user_scores")
data class UserScoreEntity(
    @PrimaryKey val userId: String,
    val registerNumber: String,
    val problemScore: Double = 0.0,
    val improvementScore: Double = 0.0,
    val refinementScore: Double = 0.0,
    val totalScore: Double = 0.0,
    val problemsCount: Int = 0,
    val improvementsCount: Int = 0,
    val refinementsCount: Int = 0
) {
    fun toDomain(): UserScore = UserScore(
        userId = userId,
        registerNumber = registerNumber,
        problemScore = problemScore,
        improvementScore = improvementScore,
        refinementScore = refinementScore,
        totalScore = totalScore,
        problemsCount = problemsCount,
        improvementsCount = improvementsCount,
        refinementsCount = refinementsCount
    )
}
