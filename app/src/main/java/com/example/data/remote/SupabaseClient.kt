package com.example.data.remote

import android.util.Log
import com.example.BuildConfig
import com.example.data.remote.api.SupabaseAuthApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Supabase client configuration and API factory for the LIFE application.
 * Manages database endpoint connection, API keys, authenticated HTTP client,
 * and Supabase GoTrue authentication endpoints.
 */
object SupabaseClient {

    private const val TAG = "SupabaseClient"

    val currentAccessToken = AtomicReference<String?>(null)

    val supabaseUrl: String by lazy {
        try {
            val url = BuildConfig.SUPABASE_URL
            if (url.isNullOrBlank() || url.contains("your-project") || url.startsWith("https://your-")) {
                "https://khjkbfkfucsdheeyvcar.supabase.co"
            } else {
                url
            }
        } catch (e: Exception) {
            "https://khjkbfkfucsdheeyvcar.supabase.co"
        }
    }

    val supabaseAnonKey: String by lazy {
        try {
            val key = BuildConfig.SUPABASE_ANON_KEY
            if (key.isNullOrBlank() || key.contains("your_supabase_anon_key") || key.startsWith("your_")) {
                "REDACTED"
            } else {
                key
            }
        } catch (e: Exception) {
            "REDACTED"
        }
    }

    private val authInterceptor = Interceptor { chain ->
        val original = chain.request()
        val path = original.url.encodedPath
        val builder = original.newBuilder()

        // Always inject the public API key header
        builder.header("apikey", supabaseAnonKey)

        // Only inject Authorization if the request does NOT already have an Authorization header
        val existingAuth = original.header("Authorization")
        if (existingAuth.isNullOrBlank()) {
            val userToken = currentAccessToken.get()
            if (!userToken.isNullOrBlank()) {
                builder.header("Authorization", "Bearer $userToken")
            } else if (!path.contains("/auth/v1/")) {
                // For PostgREST and public RPCs, provide anon Bearer token
                builder.header("Authorization", "Bearer $supabaseAnonKey")
            }
        }

        if (original.header("Content-Type") == null) {
            builder.header("Content-Type", "application/json")
        }
        if (original.header("Prefer") == null && path.contains("/rest/v1/")) {
            builder.header("Prefer", "return=representation")
        }

        chain.proceed(builder.build())
    }

    private val loggingInterceptor = HttpLoggingInterceptor { message ->
        // Sanitize sensitive tokens, keys, passwords and OTPs from logcat
        if (!message.contains("Bearer ") && !message.contains("password") && !message.contains("otp")) {
            Log.d(TAG, message)
        }
    }.apply {
        redactHeader("Authorization")
        redactHeader("apikey")
        level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
    }

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    val moshi: Moshi by lazy {
        Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    val retrofit: Retrofit by lazy {
        val baseUrl = if (supabaseUrl.endsWith("/")) supabaseUrl else "$supabaseUrl/"
        Retrofit.Builder()
            .baseUrl("${baseUrl}rest/v1/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    val authRetrofit: Retrofit by lazy {
        val baseUrl = if (supabaseUrl.endsWith("/")) supabaseUrl else "$supabaseUrl/"
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    val authApi: SupabaseAuthApi by lazy {
        authRetrofit.create(SupabaseAuthApi::class.java)
    }

    val restApi: SupabaseApi by lazy {
        retrofit.create(SupabaseApi::class.java)
    }

    inline fun <reified T> createService(): T = retrofit.create(T::class.java)
}
