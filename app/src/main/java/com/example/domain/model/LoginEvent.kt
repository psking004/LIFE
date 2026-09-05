package com.example.domain.model

data class LoginEvent(
    val id: String,
    val userId: String,
    val registerNumber: String,
    val userName: String,
    val loginAt: Long = System.currentTimeMillis(),
    val loginStatus: String = "SUCCESS",
    val deviceType: String = "Android",
    val appVersion: String = "1.0.0",
    val sessionId: String = ""
)
