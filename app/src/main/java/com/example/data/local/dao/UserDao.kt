package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.entities.UserEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Query("SELECT * FROM users WHERE registerNumber = :registerNumber LIMIT 1")
    suspend fun getUserByRegisterNumber(registerNumber: String): UserEntity?

    @Query("SELECT * FROM users WHERE id = :id LIMIT 1")
    suspend fun getUserById(id: String): UserEntity?

    @Query("SELECT * FROM users WHERE id = :id LIMIT 1")
    fun observeUserById(id: String): Flow<UserEntity?>

    @Query("SELECT * FROM users ORDER BY registerNumber ASC")
    fun getAllUsers(): Flow<List<UserEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: UserEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertUsers(users: List<UserEntity>)

    @Update
    suspend fun updateUser(user: UserEntity)

    @Query("UPDATE users SET accountStatus = :status, isSuspended = :isSuspended, approvedAt = :approvedAt, approvedBy = :approvedBy WHERE id = :id")
    suspend fun updateAccountStatus(
        id: String,
        status: String,
        isSuspended: Boolean,
        approvedAt: Long? = null,
        approvedBy: String? = null
    )

    @Query("UPDATE users SET isSuspended = :isSuspended, accountStatus = CASE WHEN :isSuspended = 1 THEN 'SUSPENDED' ELSE 'ACTIVE' END WHERE id = :id")
    suspend fun setSuspended(id: String, isSuspended: Boolean)

    @Query("UPDATE users SET isFirstLogin = :isFirstLogin WHERE id = :id")
    suspend fun updateFirstLoginStatus(id: String, isFirstLogin: Boolean)

    // Password management is delegated to Firebase Auth; do not store password hashes locally.

    @Query("UPDATE users SET authUserId = :authUserId WHERE id = :id")
    suspend fun updateAuthUserId(id: String, authUserId: String)

    @Query("DELETE FROM users WHERE id = :id")
    suspend fun deleteUserById(id: String)

    @Query("SELECT * FROM users WHERE authUserId = :authUserId LIMIT 1")
    suspend fun getUserByAuthUserId(authUserId: String): UserEntity?

    @Query("DELETE FROM users WHERE id = 'user_admin' OR (isAdmin = 1 AND id != :activeAdminId)")
    suspend fun clearStaleAdminUsers(activeAdminId: String)
}
