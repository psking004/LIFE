package com.example.domain.model

data class User(
    val id: String,
    val authUserId: String? = null,
    val registerNumber: String,
    val name: String = "",
    val phoneNumber: String = "",
    val department: String = "Computer Science & Engineering",
    val accountStatus: AccountStatus = AccountStatus.ACTIVE,
    val isFirstLogin: Boolean = false,
    val isSuspended: Boolean = (accountStatus == AccountStatus.SUSPENDED),
    val isAdmin: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val approvedAt: Long? = null,
    val approvedBy: String? = null
)
