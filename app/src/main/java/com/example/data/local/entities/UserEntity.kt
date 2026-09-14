package com.example.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.domain.model.AccountStatus
import com.example.domain.model.User

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: String,
    val authUserId: String? = null,
    val registerNumber: String,
    val passwordHash: String,
    val name: String = "",
    val phoneNumber: String = "",
    val department: String = "Computer Science & Engineering",
    val email: String = "",
    val emailVerified: Boolean = false,
    val accountStatus: String = "ACTIVE",
    val isFirstLogin: Boolean = false,
    val isSuspended: Boolean = false,
    val isAdmin: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val approvedAt: Long? = null,
    val approvedBy: String? = null
) {
    fun toDomain(): User {
        val status = try {
            AccountStatus.valueOf(accountStatus)
        } catch (e: Exception) {
            if (isSuspended) AccountStatus.SUSPENDED else AccountStatus.ACTIVE
        }
        return User(
            id = id,
            authUserId = authUserId,
            registerNumber = registerNumber,
            name = name.ifEmpty { if (isAdmin) "Administrator" else "Student $registerNumber" },
            phoneNumber = phoneNumber.ifEmpty { "9876541234" },
            department = department.ifEmpty { "Computer Science & Engineering" },
            email = email,
            emailVerified = emailVerified,
            accountStatus = status,
            isFirstLogin = isFirstLogin,
            isSuspended = isSuspended || status == AccountStatus.SUSPENDED,
            isAdmin = isAdmin,
            createdAt = createdAt,
            approvedAt = approvedAt,
            approvedBy = approvedBy
        )
    }
}
