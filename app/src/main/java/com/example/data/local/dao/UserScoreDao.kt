package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.entities.UserScoreEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserScoreDao {
    @Query("SELECT * FROM user_scores WHERE userId = :userId LIMIT 1")
    fun observeUserScore(userId: String): Flow<UserScoreEntity?>

    @Query("SELECT * FROM user_scores WHERE userId = :userId LIMIT 1")
    suspend fun getUserScore(userId: String): UserScoreEntity?

    @Query("SELECT * FROM user_scores ORDER BY problemScore DESC, problemsCount DESC")
    fun getTopProblemContributors(): Flow<List<UserScoreEntity>>

    @Query("SELECT * FROM user_scores ORDER BY improvementScore DESC, improvementsCount DESC")
    fun getTopImprovementContributors(): Flow<List<UserScoreEntity>>

    @Query("SELECT * FROM user_scores ORDER BY refinementScore DESC, refinementsCount DESC")
    fun getTopRefiners(): Flow<List<UserScoreEntity>>

    @Query("SELECT * FROM user_scores ORDER BY totalScore DESC")
    fun getAllScores(): Flow<List<UserScoreEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUserScore(score: UserScoreEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertUserScores(scores: List<UserScoreEntity>)

    @Update
    suspend fun updateUserScore(score: UserScoreEntity)
}
