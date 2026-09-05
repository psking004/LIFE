package com.example.domain.model

data class Vote(
    val id: String,
    val userId: String,
    val nodeId: String,
    val createdAt: Long = System.currentTimeMillis()
)
