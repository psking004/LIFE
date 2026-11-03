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



    /**
     * Supabase project URL, injected at build time from `local.properties`
     * (`SUPABASE_URL=...`). See `local.properties.example` and the README.
     */
    val supabaseUrl: String by lazy {
        val url = BuildConfig.SUPABASE_URL
        check(url.isNotBlank() && !url.contains("your-project")) {
            "SUPABASE_URL is not configured. Add it to local.properties (see local.properties.example)."
        }
        url
    }

    /**
     * Supabase anon (public) key, injected at build time from `local.properties`
     * (`SUPABASE_ANON_KEY=...`). Never hard-code it in source.
     */
    val supabaseAnonKey: String by lazy {
        val key = BuildConfig.SUPABASE_ANON_KEY
        check(key.isNotBlank() && !key.startsWith("your_")) {
            "SUPABASE_ANON_KEY is not configured. Add it to local.properties (see local.properties.example)."
        }
        key
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

            .add(SupabaseUserDto::class.java, SupabaseUserDtoAdapter())

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

