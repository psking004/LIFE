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
            BuildConfig.SUPABASE_URL.ifEmpty { "https://khjkbfkfucsdheeyvcar.supabase.co" }
        } catch (e: Exception) {
            "https://khjkbfkfucsdheeyvcar.supabase.co"
        }
    }

    val supabaseAnonKey: String by lazy {
        try {
            BuildConfig.SUPABASE_ANON_KEY.ifEmpty {
                "REDACTED"
            }
        } catch (e: Exception) {
            "REDACTED"
        }
    }

    private val authInterceptor = Interceptor { chain ->
        val original = chain.request()
        val userToken = currentAccessToken.get()
        val bearerToken = if (!userToken.isNullOrBlank()) userToken else supabaseAnonKey
        
        val requestBuilder = original.newBuilder()
            .header("apikey", supabaseAnonKey)
            .header("Authorization", "Bearer $bearerToken")
            .header("Content-Type", "application/json")
            .header("Prefer", "return=representation")
        chain.proceed(requestBuilder.build())
    }

    private val loggingInterceptor = HttpLoggingInterceptor { message ->
        Log.d(TAG, message)
    }.apply {
        level = HttpLoggingInterceptor.Level.BASIC
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
