package com.example.data.remote.api

import com.example.data.remote.dto.SupabaseAuthTokenResponse
import com.example.data.remote.dto.SupabaseAuthUserDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT

interface SupabaseAuthApi {

    @POST("auth/v1/token?grant_type=password")
    suspend fun signInWithPassword(
        @Body request: Map<String, String>
    ): Response<SupabaseAuthTokenResponse>

    @POST("auth/v1/signup")
    suspend fun signUp(
        @Body request: Map<String, @JvmSuppressWildcards Any>
    ): Response<SupabaseAuthTokenResponse>

    @POST("auth/v1/token?grant_type=refresh_token")
    suspend fun refreshToken(
        @Body request: Map<String, String>
    ): Response<SupabaseAuthTokenResponse>

    @GET("auth/v1/user")
    suspend fun getUser(
        @Header("Authorization") bearerToken: String
    ): Response<SupabaseAuthUserDto>

    @PUT("auth/v1/user")
    suspend fun updateUser(
        @Header("Authorization") bearerToken: String,
        @Body updates: Map<String, @JvmSuppressWildcards Any?>
    ): Response<SupabaseAuthUserDto>

    @POST("auth/v1/logout")
    suspend fun logout(
        @Header("Authorization") bearerToken: String
    ): Response<Unit>

    @POST("auth/v1/factors")
    suspend fun enrollFactor(
        @Header("Authorization") bearerToken: String,
        @Body request: Map<String, String>
    ): Response<com.example.data.remote.dto.SupabaseFactorDto>

    @POST("auth/v1/factors/{factorId}/challenge")
    suspend fun createChallenge(
        @Header("Authorization") bearerToken: String,
        @retrofit2.http.Path("factorId") factorId: String,
        @Body request: Map<String, String> = emptyMap()
    ): Response<com.example.data.remote.dto.SupabaseChallengeDto>

    @POST("auth/v1/factors/{factorId}/verify")
    suspend fun verifyFactor(
        @Header("Authorization") bearerToken: String,
        @retrofit2.http.Path("factorId") factorId: String,
        @Body request: Map<String, String>
    ): Response<SupabaseAuthTokenResponse>

    // Legacy OTP - removing these as we switch to MFA factors
    @POST("auth/v1/otp")
    suspend fun sendOtp(
        @Body request: Map<String, @JvmSuppressWildcards Any>
    ): Response<Map<String, @JvmSuppressWildcards Any?>>

    @POST("auth/v1/verify")
    suspend fun verifyOtp(
        @Body request: Map<String, @JvmSuppressWildcards Any>
    ): Response<SupabaseAuthTokenResponse>
}
