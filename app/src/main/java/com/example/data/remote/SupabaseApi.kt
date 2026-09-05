package com.example.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Query

@JsonClass(generateAdapter = true)
data class SupabaseUserDto(
    @Json(name = "id") val id: String,
    @Json(name = "register_number") val registerNumber: String,
    @Json(name = "password_hash") val passwordHash: String = "student123",
    @Json(name = "is_first_login") val isFirstLogin: Boolean = false,
    @Json(name = "is_suspended") val isSuspended: Boolean = false,
    @Json(name = "is_admin") val isAdmin: Boolean = false,
    @Json(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

@JsonClass(generateAdapter = true)
data class SupabaseIdeaNodeDto(
    @Json(name = "id") val id: String,
    @Json(name = "title") val title: String,
    @Json(name = "description") val description: String,
    @Json(name = "type") val type: String,
    @Json(name = "author_id") val authorId: String,
    @Json(name = "author_register_number") val authorRegisterNumber: String,
    @Json(name = "parent_id") val parentId: String? = null,
    @Json(name = "votes_count") val votesCount: Int = 0,
    @Json(name = "is_deleted") val isDeleted: Boolean = false,
    @Json(name = "created_at") val createdAt: Long,
    @Json(name = "updated_at") val updatedAt: Long
)

@JsonClass(generateAdapter = true)
data class SupabaseVoteDto(
    @Json(name = "id") val id: String,
    @Json(name = "node_id") val nodeId: String,
    @Json(name = "user_id") val userId: String,
    @Json(name = "created_at") val createdAt: Long
)

@JsonClass(generateAdapter = true)
data class SupabaseUserScoreDto(
    @Json(name = "user_id") val userId: String,
    @Json(name = "register_number") val registerNumber: String,
    @Json(name = "problem_score") val problemScore: Double = 0.0,
    @Json(name = "improvement_score") val improvementScore: Double = 0.0,
    @Json(name = "refinement_score") val refinementScore: Double = 0.0,
    @Json(name = "total_score") val totalScore: Double = 0.0,
    @Json(name = "problems_count") val problemsCount: Int = 0,
    @Json(name = "improvements_count") val improvementsCount: Int = 0,
    @Json(name = "refinements_count") val refinementsCount: Int = 0
)

/**
 * Retrofit interface for Supabase PostgREST API.
 */
interface SupabaseApi {

    // --- Users ---
    @GET("users")
    suspend fun getUsers(@Query("select") select: String = "*"): List<SupabaseUserDto>

    @POST("users")
    suspend fun upsertUser(@Body user: SupabaseUserDto): Response<List<SupabaseUserDto>>

    // --- Idea Nodes ---
    @GET("idea_nodes")
    suspend fun getIdeaNodes(
        @Query("select") select: String = "*",
        @Query("order") order: String = "created_at.desc"
    ): List<SupabaseIdeaNodeDto>

    @POST("idea_nodes")
    suspend fun insertIdeaNode(@Body node: SupabaseIdeaNodeDto): Response<List<SupabaseIdeaNodeDto>>

    @PATCH("idea_nodes")
    suspend fun updateIdeaNode(
        @Query("id") idFilter: String,
        @Body updates: Map<String, Any?>
    ): Response<List<SupabaseIdeaNodeDto>>

    @DELETE("idea_nodes")
    suspend fun deleteIdeaNode(@Query("id") idFilter: String): Response<Unit>

    // --- Votes ---
    @GET("votes")
    suspend fun getVotes(@Query("select") select: String = "*"): List<SupabaseVoteDto>

    @POST("votes")
    suspend fun insertVote(@Body vote: SupabaseVoteDto): Response<List<SupabaseVoteDto>>

    @DELETE("votes")
    suspend fun deleteVote(
        @Query("node_id") nodeFilter: String,
        @Query("user_id") userFilter: String
    ): Response<Unit>

    // --- User Scores ---
    @GET("user_scores")
    suspend fun getUserScores(@Query("select") select: String = "*"): List<SupabaseUserScoreDto>

    @POST("user_scores")
    suspend fun upsertUserScore(@Body score: SupabaseUserScoreDto): Response<List<SupabaseUserScoreDto>>
}
