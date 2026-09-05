package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entities.VoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VoteDao {
    @Query("SELECT * FROM votes WHERE userId = :userId AND nodeId = :nodeId LIMIT 1")
    suspend fun getVote(userId: String, nodeId: String): VoteEntity?

    @Query("SELECT * FROM votes WHERE userId = :userId AND nodeId = :nodeId LIMIT 1")
    fun observeVote(userId: String, nodeId: String): Flow<VoteEntity?>

    @Query("SELECT * FROM votes WHERE userId = :userId")
    fun getVotesByUser(userId: String): Flow<List<VoteEntity>>

    @Query("SELECT nodeId FROM votes WHERE userId = :userId")
    fun getVotedNodeIdsByUser(userId: String): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM votes WHERE nodeId = :nodeId")
    suspend fun countVotesForNode(nodeId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVote(vote: VoteEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertVotes(votes: List<VoteEntity>)

    @Query("DELETE FROM votes WHERE userId = :userId AND nodeId = :nodeId")
    suspend fun deleteVote(userId: String, nodeId: String)

    @Query("DELETE FROM votes WHERE nodeId = :nodeId")
    suspend fun deleteVotesForNode(nodeId: String)
}
