package com.example.data.remote



import com.squareup.moshi.FromJson

import com.squareup.moshi.Json

import com.squareup.moshi.JsonClass

import com.squareup.moshi.JsonQualifier

import com.squareup.moshi.JsonReader

import com.squareup.moshi.JsonWriter

import com.squareup.moshi.ToJson

import retrofit2.Response

import retrofit2.http.Body

import retrofit2.http.DELETE

import retrofit2.http.GET

import retrofit2.http.PATCH

import retrofit2.http.POST

import retrofit2.http.Query



fun parseIsoOrMillisToLong(str: String?): Long? {

    if (str.isNullOrBlank()) return null

    str.toLongOrNull()?.let { return it }



    try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            return java.time.OffsetDateTime.parse(str).toInstant().toEpochMilli()
        } else {
            val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX", java.util.Locale.US)
            return format.parse(str)?.time
        }
    } catch (ignored: Throwable) {}

    try {

        return java.time.Instant.parse(str).toEpochMilli()

    } catch (ignored: Throwable) {}



    val patterns = arrayOf(

        "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX",

        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",

        "yyyy-MM-dd'T'HH:mm:ssXXX",

        "yyyy-MM-dd'T'HH:mm:ss'Z'",

        "yyyy-MM-dd HH:mm:ssXXX",

        "yyyy-MM-dd HH:mm:ss"

    )

    for (pattern in patterns) {

        try {

            val sdf = java.text.SimpleDateFormat(pattern, java.util.Locale.US)

            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")

            val date = sdf.parse(str)

            if (date != null) return date.time

        } catch (ignored: Throwable) {}

    }

    return null

}



data class SupabaseUserDto(

    @Json(name = "id") val id: String? = null,

    @Json(name = "register_number") val registerNumber: String? = null,

    @Json(name = "email") val email: String? = null,

    @Json(name = "email_verified") val emailVerified: Boolean? = null,

    @Json(name = "email_verified_at") val emailVerifiedAt: Long? = null,

    @Json(name = "otp_verified_until") val otpVerifiedUntil: Long? = null,

    @Json(name = "is_first_login") val isFirstLogin: Boolean? = null,

    @Json(name = "is_suspended") val isSuspended: Boolean? = null,

    @Json(name = "is_admin") val isAdmin: Boolean? = null,

    @Json(name = "created_at") val createdAt: Long? = null,

    @Json(name = "auth_user_id") val authUserId: String? = null,

    @Json(name = "name") val name: String? = null,

    @Json(name = "phone_number") val phoneNumber: String? = null,

    @Json(name = "department") val department: String? = null,

    @Json(name = "account_status") val accountStatus: String? = null,

    @Json(name = "approved_at") val approvedAt: Long? = null,

    @Json(name = "approved_by") val approvedBy: String? = null

)



class SupabaseUserDtoAdapter : com.squareup.moshi.JsonAdapter<SupabaseUserDto>() {

    override fun fromJson(reader: JsonReader): SupabaseUserDto {

        var id: String? = null

        var registerNumber: String? = null

        var email: String? = null

        var emailVerified: Boolean? = null

        var emailVerifiedAt: Long? = null

        var otpVerifiedUntil: Long? = null

        var isFirstLogin: Boolean? = null

        var isSuspended: Boolean? = null

        var isAdmin: Boolean? = null

        var createdAt: Long? = null

        var authUserId: String? = null

        var name: String? = null

        var phoneNumber: String? = null

        var department: String? = null

        var accountStatus: String? = null

        var approvedAt: Long? = null

        var approvedBy: String? = null



        reader.beginObject()

        while (reader.hasNext()) {

            val fieldName = reader.nextName()

            if (reader.peek() == JsonReader.Token.NULL) {

                reader.nextNull<Unit>()

                continue

            }

            when (fieldName) {

                "id" -> id = reader.nextString()

                "register_number" -> registerNumber = reader.nextString()

                "email" -> email = reader.nextString()

                "email_verified" -> emailVerified = when (reader.peek()) {

                    JsonReader.Token.BOOLEAN -> reader.nextBoolean()

                    else -> reader.nextString().toBooleanStrictOrNull() ?: false

                }

                "email_verified_at" -> emailVerifiedAt = parseTimestampToken(reader)

                "otp_verified_until" -> otpVerifiedUntil = parseTimestampToken(reader)

                "is_first_login" -> isFirstLogin = when (reader.peek()) {

                    JsonReader.Token.BOOLEAN -> reader.nextBoolean()

                    else -> reader.nextString().toBooleanStrictOrNull() ?: false

                }

                "is_suspended" -> isSuspended = when (reader.peek()) {

                    JsonReader.Token.BOOLEAN -> reader.nextBoolean()

                    else -> reader.nextString().toBooleanStrictOrNull() ?: false

                }

                "is_admin" -> isAdmin = when (reader.peek()) {

                    JsonReader.Token.BOOLEAN -> reader.nextBoolean()

                    else -> reader.nextString().toBooleanStrictOrNull() ?: false

                }

                "created_at" -> createdAt = parseTimestampToken(reader)

                "auth_user_id" -> authUserId = reader.nextString()

                "name" -> name = reader.nextString()

                "phone_number" -> phoneNumber = reader.nextString()

                "department" -> department = reader.nextString()

                "account_status" -> accountStatus = reader.nextString()

                "approved_at" -> approvedAt = parseTimestampToken(reader)

                "approved_by" -> approvedBy = reader.nextString()

                else -> reader.skipValue()

            }

        }

        reader.endObject()



        return SupabaseUserDto(

            id = id,

            registerNumber = registerNumber,

            email = email,

            emailVerified = emailVerified,

            emailVerifiedAt = emailVerifiedAt,

            otpVerifiedUntil = otpVerifiedUntil,

            isFirstLogin = isFirstLogin,

            isSuspended = isSuspended,

            isAdmin = isAdmin,

            createdAt = createdAt,

            authUserId = authUserId,

            name = name,

            phoneNumber = phoneNumber,

            department = department,

            accountStatus = accountStatus,

            approvedAt = approvedAt,

            approvedBy = approvedBy

        )

    }



    override fun toJson(writer: JsonWriter, value: SupabaseUserDto?) {

        if (value == null) {

            writer.nullValue()

            return

        }

        writer.beginObject()

        value.id?.let { writer.name("id").value(it) }

        value.registerNumber?.let { writer.name("register_number").value(it) }

        value.email?.let { writer.name("email").value(it) }

        value.emailVerified?.let { writer.name("email_verified").value(it) }

        value.emailVerifiedAt?.let { writer.name("email_verified_at").value(it) }

        value.otpVerifiedUntil?.let { writer.name("otp_verified_until").value(it) }

        value.isFirstLogin?.let { writer.name("is_first_login").value(it) }

        value.isSuspended?.let { writer.name("is_suspended").value(it) }

        value.isAdmin?.let { writer.name("is_admin").value(it) }

        value.createdAt?.let { writer.name("created_at").value(it) }

        value.authUserId?.let { writer.name("auth_user_id").value(it) }

        value.name?.let { writer.name("name").value(it) }

        value.phoneNumber?.let { writer.name("phone_number").value(it) }

        value.department?.let { writer.name("department").value(it) }

        value.accountStatus?.let { writer.name("account_status").value(it) }

        value.approvedAt?.let { writer.name("approved_at").value(it) }

        value.approvedBy?.let { writer.name("approved_by").value(it) }

        writer.endObject()

    }



    private fun parseTimestampToken(reader: JsonReader): Long? {

        return when (reader.peek()) {

            JsonReader.Token.NUMBER -> reader.nextLong()

            JsonReader.Token.STRING -> parseIsoOrMillisToLong(reader.nextString())

            else -> {

                reader.skipValue()

                null

            }

        }

    }

}



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

    suspend fun getUsersResponse(@Query("select") select: String = "*"): Response<List<SupabaseUserDto>>



    @GET("users")

    suspend fun getUsers(@Query("select") select: String = "*"): List<SupabaseUserDto>



    @GET("users")

    suspend fun getUserByAuthId(@Query("auth_user_id") authUserIdFilter: String): List<SupabaseUserDto>

    @GET("users")
    suspend fun getUserByRegisterNumber(@Query("register_number") registerNumberFilter: String): List<SupabaseUserDto>

    @PATCH("users")
    suspend fun updateUser(
        @Query("id") idFilter: String,
        @Body updates: Map<String, @JvmSuppressWildcards Any?>
    ): Response<List<SupabaseUserDto>>

    @PATCH("users")
    suspend fun updateUserByAuthId(
        @Query("auth_user_id") authUserIdFilter: String,
        @Body updates: Map<String, @JvmSuppressWildcards Any?>
    ): Response<List<SupabaseUserDto>>



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



    @POST("rpc/cast_vote")

    suspend fun castVote(@Body body: Map<String, String>): Response<Map<String, Any>>



    @POST("rpc/remove_vote")

    suspend fun removeVote(@Body body: Map<String, String>): Response<Map<String, Any>>



    // --- User Scores ---

    @GET("user_scores")

    suspend fun getUserScores(@Query("select") select: String = "*"): List<SupabaseUserScoreDto>



    @POST("user_scores")

    suspend fun upsertUserScore(@Body score: SupabaseUserScoreDto): Response<List<SupabaseUserScoreDto>>

}

