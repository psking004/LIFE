package com.example.domain.model

data class LeaderboardRow(
    val rank: Int,
    val displayRank: String, // Handles ties e.g. "#1", "#1", "#3"
    val userId: String,
    val registerNumber: String,
    val score: Double,
    val count: Int
)
