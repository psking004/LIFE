package com.example.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.domain.model.LoginEvent

@Entity(tableName = "login_events")
data class LoginEventEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val registerNumber: String,
    val userName: String,
    val loginAt: Long = System.currentTimeMillis(),
    val loginStatus: String = "SUCCESS",
    val deviceType: String = "Android",
    val appVersion: String = "1.0.0",
    val sessionId: String = ""
) {
    fun toDomain(): LoginEvent = LoginEvent(
        id = id,
        userId = userId,
        registerNumber = registerNumber,
        userName = userName,
        loginAt = loginAt,
        loginStatus = loginStatus,
        deviceType = deviceType,
        appVersion = appVersion,
        sessionId = sessionId
    )
}
