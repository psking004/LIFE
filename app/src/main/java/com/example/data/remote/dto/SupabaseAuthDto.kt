package com.example.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class SupabaseFactorDto(
    @param:Json(name = "id") val id: String,
    @param:Json(name = "factor_type") val factorType: String,
    @param:Json(name = "status") val status: String,
    @param:Json(name = "phone") val phone: String? = null
)

@JsonClass(generateAdapter = true)
data class SupabaseChallengeDto(
    @param:Json(name = "id") val id: String,
    @param:Json(name = "expires_at") val expiresAt: String? = null
)

@JsonClass(generateAdapter = true)
data class SupabaseAuthUserDto(
    @param:Json(name = "id") val id: String,
    @param:Json(name = "email") val email: String? = null,
    @param:Json(name = "user_metadata") val userMetadata: Map<String, Any?>? = null,
    @param:Json(name = "app_metadata") val appMetadata: Map<String, Any?>? = null,
    @param:Json(name = "factors") val factors: List<SupabaseFactorDto>? = null,
    @param:Json(name = "created_at") val createdAt: String? = null,
    @param:Json(name = "updated_at") val updatedAt: String? = null
)

@JsonClass(generateAdapter = true)
data class SupabaseAuthTokenResponse(
    @param:Json(name = "access_token") val accessToken: String? = null,
    @param:Json(name = "token_type") val tokenType: String? = null,
    @param:Json(name = "expires_in") val expiresIn: Long? = null,
    @param:Json(name = "refresh_token") val refreshToken: String? = null,
    @param:Json(name = "user") val user: SupabaseAuthUserDto? = null,
    @param:Json(name = "error") val error: String? = null,
    @param:Json(name = "error_description") val errorDescription: String? = null,
    @param:Json(name = "error_code") val errorCode: String? = null,
    @param:Json(name = "msg") val msg: String? = null
)
