package com.example.data.remote

import android.util.Log
import com.example.data.local.AppDatabase
import com.example.data.local.entities.IdeaNodeEntity
import com.example.data.local.entities.UserEntity
import com.example.data.local.entities.UserScoreEntity
import com.example.data.local.entities.VoteEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handles background synchronization between local Room database and Supabase remote database.
 * Gracefully handles offline states, remote failures, and network timeouts.
 */
class SupabaseSyncManager(
    private val database: AppDatabase,
    private val api: SupabaseApi = SupabaseClient.createService()
) {
    companion object {
        private const val TAG = "SupabaseSyncManager"
    }

    /**
     * Pulls latest users, nodes, votes, and scores from Supabase into Room database.
     */
    suspend fun syncFromRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting sync from Supabase (${SupabaseClient.supabaseUrl})...")

            // 1. Sync Users
            try {
                val remoteUsers = api.getUsers()
                if (remoteUsers.isNotEmpty()) {
                    val userEntities = remoteUsers.map { dto ->
                        UserEntity(
                            id = dto.id,
                            registerNumber = dto.registerNumber,
                            passwordHash = dto.passwordHash,
                            isFirstLogin = dto.isFirstLogin,
                            isSuspended = dto.isSuspended,
                            isAdmin = dto.isAdmin,
                            createdAt = dto.createdAt
                        )
                    }
                    database.userDao().insertUsers(userEntities)
                    Log.d(TAG, "Synced ${userEntities.size} users from Supabase")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Users sync skipped or table empty: ${e.message}")
            }

            // 2. Sync Idea Nodes
            try {
                val remoteNodes = api.getIdeaNodes()
                if (remoteNodes.isNotEmpty()) {
                    val nodeEntities = remoteNodes.map { dto ->
                        IdeaNodeEntity(
                            id = dto.id,
                            title = dto.title,
                            description = dto.description,
                            type = dto.type,
                            authorId = dto.authorId,
                            authorRegisterNumber = dto.authorRegisterNumber,
                            parentId = dto.parentId,
                            votesCount = dto.votesCount,
                            isDeleted = dto.isDeleted,
                            createdAt = dto.createdAt,
                            updatedAt = dto.updatedAt
                        )
                    }
                    database.ideaNodeDao().insertNodes(nodeEntities)
                    Log.d(TAG, "Synced ${nodeEntities.size} nodes from Supabase")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Idea nodes sync skipped or table empty: ${e.message}")
            }

            // 3. Sync Votes
            try {
                val remoteVotes = api.getVotes()
                if (remoteVotes.isNotEmpty()) {
                    val voteEntities = remoteVotes.map { dto ->
                        VoteEntity(
                            id = dto.id,
                            nodeId = dto.nodeId,
                            userId = dto.userId,
                            createdAt = dto.createdAt
                        )
                    }
                    database.voteDao().insertVotes(voteEntities)
                    Log.d(TAG, "Synced ${voteEntities.size} votes from Supabase")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Votes sync skipped or table empty: ${e.message}")
            }

            // 4. Sync Scores
            try {
                val remoteScores = api.getUserScores()
                if (remoteScores.isNotEmpty()) {
                    val scoreEntities = remoteScores.map { dto ->
                        UserScoreEntity(
                            userId = dto.userId,
                            registerNumber = dto.registerNumber,
                            problemScore = dto.problemScore,
                            improvementScore = dto.improvementScore,
                            refinementScore = dto.refinementScore,
                            totalScore = dto.totalScore,
                            problemsCount = dto.problemsCount,
                            improvementsCount = dto.improvementsCount,
                            refinementsCount = dto.refinementsCount
                        )
                    }
                    database.userScoreDao().insertUserScores(scoreEntities)
                    Log.d(TAG, "Synced ${scoreEntities.size} scores from Supabase")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Scores sync skipped or table empty: ${e.message}")
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Supabase sync error: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Pushes a newly created IdeaNode to Supabase asynchronously.
     */
    suspend fun pushNode(node: IdeaNodeEntity) = withContext(Dispatchers.IO) {
        try {
            val dto = SupabaseIdeaNodeDto(
                id = node.id,
                title = node.title,
                description = node.description,
                type = node.type,
                authorId = node.authorId,
                authorRegisterNumber = node.authorRegisterNumber,
                parentId = node.parentId,
                votesCount = node.votesCount,
                isDeleted = node.isDeleted,
                createdAt = node.createdAt,
                updatedAt = node.updatedAt
            )
            api.insertIdeaNode(dto)
            Log.d(TAG, "Successfully pushed node ${node.id} to Supabase")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to push node to Supabase (offline/table missing): ${e.message}")
        }
    }

    /**
     * Pushes a vote record to Supabase.
     */
    suspend fun pushVote(vote: VoteEntity) = withContext(Dispatchers.IO) {
        try {
            val dto = SupabaseVoteDto(
                id = vote.id,
                nodeId = vote.nodeId,
                userId = vote.userId,
                createdAt = vote.createdAt
            )
            api.insertVote(dto)
            Log.d(TAG, "Successfully pushed vote ${vote.id} to Supabase")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to push vote to Supabase: ${e.message}")
        }
    }

    /**
     * Deletes a vote record on Supabase when unvoted.
     */
    suspend fun deleteVote(nodeId: String, userId: String) = withContext(Dispatchers.IO) {
        try {
            api.deleteVote(idFilter("eq.$nodeId"), idFilter("eq.$userId"))
            Log.d(TAG, "Successfully removed vote for node $nodeId from Supabase")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove vote from Supabase: ${e.message}")
        }
    }

    private fun idFilter(value: String): String = value
}
