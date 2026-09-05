package com.example.domain.model

data class UserScore(
    val userId: String,
    val registerNumber: String,
    val problemScore: Double = 0.0,
    val improvementScore: Double = 0.0,
    val refinementScore: Double = 0.0,
    val totalScore: Double = 0.0,
    val problemsCount: Int = 0,
    val improvementsCount: Int = 0,
    val refinementsCount: Int = 0
)
