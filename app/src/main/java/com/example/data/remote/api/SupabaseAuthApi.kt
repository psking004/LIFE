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

    @POST("functions/v1/send-login-otp")
    suspend fun sendLoginOtp(
        @Header("Authorization") bearerToken: String
    ): Response<com.example.data.remote.dto.SendLoginOtpResponse>

    @POST("functions/v1/verify-login-otp")
    suspend fun verifyLoginOtp(
        @Header("Authorization") bearerToken: String,
        @Body request: Map<String, String>
    ): Response<com.example.data.remote.dto.VerifyLoginOtpResponse>

    @POST("rest/v1/rpc/resolve_login_identity")
    suspend fun resolveLoginIdentityRpc(
        @Body request: Map<String, String>
    ): Response<com.example.data.remote.dto.ResolveLoginIdentityResponse>

    @POST("functions/v1/resolve-login-identity")
    suspend fun resolveLoginIdentityFunction(
        @Body request: Map<String, String>
    ): Response<com.example.data.remote.dto.ResolveLoginIdentityResponse>

    @POST("functions/v1/admin-manage-user")
    suspend fun adminManageUser(
        @Header("Authorization") bearerToken: String,
        @Body request: Map<String, @JvmSuppressWildcards Any?>
    ): Response<com.example.data.remote.dto.AdminManageUserResponse>

    @POST("functions/v1/self-signup")
    suspend fun selfSignUp(
        @Body request: Map<String, @JvmSuppressWildcards Any?>
    ): Response<com.example.data.remote.dto.AdminManageUserResponse>

    @POST("functions/v1/complete-first-login")
    suspend fun completeFirstLogin(
        @Header("Authorization") bearerToken: String,
        @Body request: Map<String, @JvmSuppressWildcards Any?> = emptyMap()
    ): Response<com.example.data.remote.dto.AdminManageUserResponse>
}

