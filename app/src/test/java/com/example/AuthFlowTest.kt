package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.repository.AuthRepository
import com.example.data.repository.AuthSessionManager
import com.example.data.repository.LifeRepository
import com.example.data.repository.SeedData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AuthFlowTest {

    private val mockAuthApi = object : com.example.data.remote.api.SupabaseAuthApi {
        override suspend fun signInWithPassword(request: Map<String, String>): retrofit2.Response<com.example.data.remote.dto.SupabaseAuthTokenResponse> {
            val email = request["email"] ?: ""
            val pwd = request["password"] ?: ""
            if ((email == "23cse042@life.edu" && pwd == "student123") ||
                (email == "23cse001@life.edu" && pwd == "pass123") ||
                (email == "23cse099@life.edu" && pwd == "welcome") ||
                (email == "admin01@life.edu" && pwd == "admin123")) {
                return retrofit2.Response.success(com.example.data.remote.dto.SupabaseAuthTokenResponse(
                    accessToken = "mock_access_token",
                    user = com.example.data.remote.dto.SupabaseAuthUserDto(id = "mock_user_id")
                ))
            }
            return retrofit2.Response.error(401, okhttp3.ResponseBody.create(null, ""))
        }
        
        override suspend fun enrollFactor(bearerToken: String, request: Map<String, String>): retrofit2.Response<com.example.data.remote.dto.SupabaseFactorDto> {
            return retrofit2.Response.success(com.example.data.remote.dto.SupabaseFactorDto(id = "mock_factor_id", factorType = "phone", status = "unverified"))
        }
        
        override suspend fun createChallenge(bearerToken: String, factorId: String, request: Map<String, String>): retrofit2.Response<com.example.data.remote.dto.SupabaseChallengeDto> {
            return retrofit2.Response.success(com.example.data.remote.dto.SupabaseChallengeDto(id = "mock_challenge_id"))
        }
        
        override suspend fun verifyFactor(bearerToken: String, factorId: String, request: Map<String, String>): retrofit2.Response<com.example.data.remote.dto.SupabaseAuthTokenResponse> {
            val code = request["code"]
            if (code == "123456") {
                return retrofit2.Response.success(com.example.data.remote.dto.SupabaseAuthTokenResponse(
                    accessToken = "mock_mfa_access_token",
                    expiresIn = 3600
                ))
            }
            return retrofit2.Response.error(400, okhttp3.ResponseBody.create(null, ""))
        }

        override suspend fun logout(bearerToken: String): retrofit2.Response<Unit> {
            return retrofit2.Response.success(Unit)
        }

        // Dummy implementations for unused methods
        override suspend fun signUp(request: Map<String, Any>): retrofit2.Response<com.example.data.remote.dto.SupabaseAuthTokenResponse> = retrofit2.Response.success(null)
        override suspend fun refreshToken(request: Map<String, String>): retrofit2.Response<com.example.data.remote.dto.SupabaseAuthTokenResponse> = retrofit2.Response.success(null)
        override suspend fun getUser(bearerToken: String): retrofit2.Response<com.example.data.remote.dto.SupabaseAuthUserDto> {
            return retrofit2.Response.success(com.example.data.remote.dto.SupabaseAuthUserDto(id = "mock_user_id", factors = emptyList()))
        }
        override suspend fun updateUser(bearerToken: String, updates: Map<String, Any?>): retrofit2.Response<com.example.data.remote.dto.SupabaseAuthUserDto> = retrofit2.Response.success(null)
        override suspend fun sendOtp(request: Map<String, Any>): retrofit2.Response<Map<String, Any?>> = retrofit2.Response.success(null)
        override suspend fun verifyOtp(request: Map<String, Any>): retrofit2.Response<com.example.data.remote.dto.SupabaseAuthTokenResponse> = retrofit2.Response.success(null)
    }


    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var sessionManager: AuthSessionManager
    private lateinit var authRepository: AuthRepository
    private lateinit var repository: LifeRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sessionManager = AuthSessionManager(context)
        authRepository = AuthRepository(
            userDao = database.userDao(),
            loginEventDao = database.loginEventDao(),
            sessionManager = sessionManager,
            authApi = mockAuthApi
        )
        repository = LifeRepository(database, sessionManager)

        // Preload seed users and reset session
        runTest {
            AuthRepository.resetProcessState()
            sessionManager.clearSession()
            database.userDao().insertUsers(SeedData.initialUsers)
        }
    }

    @After
    fun tearDown() {
        runTest {
            AuthRepository.resetProcessState()
            sessionManager.clearSession()
        }
        database.close()
    }

    @Test
    fun `deterministic normalization and email mapping`() {
        val reg = " 23cse042 "
        val normalized = AuthRepository.normalizeRegisterNumber(reg)
        assertEquals("23CSE042", normalized)

        val email = AuthRepository.mapToAuthEmail(normalized)
        assertEquals("23cse042@life.edu", email)

        val masked = AuthRepository.maskPhoneNumber("9876543210")
        assertEquals("******3210", masked)
    }

    @Test
    fun `correct credentials login generates OTP challenge and verifies`() = runTest {
        val challengeRes = authRepository.requestLoginOtp("23CSE042", "student123")
        assertTrue(challengeRes.isSuccess)
        val challenge = challengeRes.getOrThrow()
        assertEquals("23CSE042", challenge.registerNumber)
        assertTrue(challenge.maskedPhoneNumber.startsWith("******"))

        // Verify OTP
        val verifyRes = authRepository.verifyOtp(challenge, "123456")
        assertTrue(verifyRes.isSuccess)
        val user = verifyRes.getOrThrow()
        assertEquals("23CSE042", user.registerNumber)

        val currentUserId = sessionManager.currentUserId.first()
        assertEquals(user.id, currentUserId)

        // Verify Login Event recorded in Audit Trail
        val events = database.loginEventDao().getAllLoginEvents().first()
        assertTrue(events.any { it.registerNumber == "23CSE042" && it.loginStatus == "SUCCESS" })
    }

    @Test
    fun `wrong password fails with meaningful error`() = runTest {
        val result = authRepository.requestLoginOtp("23CSE042", "wrong_password")
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(
            exception?.message?.contains("Invalid") == true ||
            exception?.message?.contains("password") == true
        )
        
        val currentUserId = sessionManager.currentUserId.firstOrNull()
        assertNull(currentUserId)
    }

    @Test
    fun `demo student 23CSE001 login succeeds with pass123`() = runTest {
        val result = authRepository.login("23CSE001", "pass123")
        assertTrue(result.isSuccess)
        val user = result.getOrNull()
        assertNotNull(user)
        assertEquals("23CSE001", user?.registerNumber)
    }

    @Test
    fun `unknown register number fails`() = runTest {
        val result = authRepository.requestLoginOtp("99UNKNOWN00", "somepass")
        assertTrue(result.isFailure)
    }

    @Test
    fun `pending account is blocked from login until approved by admin`() = runTest {
        // 23CSE099 is initially PENDING
        val pendingUserRes = authRepository.requestLoginOtp("23CSE099", "welcome")
        assertTrue(pendingUserRes.isFailure)
        assertTrue(pendingUserRes.exceptionOrNull()?.message?.contains("awaiting") == true)

        // Admin approves account
        val approveRes = authRepository.approveStudentAccount("user_admin", "user_new_student")
        assertTrue(approveRes.isSuccess)

        // Now login succeeds
        val retryRes = authRepository.requestLoginOtp("23CSE099", "welcome")
        assertTrue(retryRes.isSuccess)
    }

    @Test
    fun `suspended account is blocked from login`() = runTest {
        // 23CSE088 is SUSPENDED in seed data
        val result = authRepository.requestLoginOtp("23CSE088", "pass123")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("suspended") == true)
    }

    @Test
    fun `first login flag triggers first login flow and password update`() = runTest {
        // Approve 23CSE099 so it can authenticate
        authRepository.approveStudentAccount("user_admin", "user_new_student")

        val loginResult = authRepository.login("23CSE099", "welcome")
        assertTrue(loginResult.isSuccess)
        val user = loginResult.getOrThrow()
        assertTrue(user.isFirstLogin)

        // Update password
        val updateResult = authRepository.setupFirstLoginPassword(user.id, "newSecurePass123")
        assertTrue(updateResult.isSuccess)

        // Verify updated in database
        val updatedUser = database.userDao().getUserById(user.id)
        assertNotNull(updatedUser)
        assertFalse(updatedUser!!.isFirstLogin)
        assertEquals("newSecurePass123", updatedUser.passwordHash)
    }

    @Test
    fun `logout clears session`() = runTest {
        authRepository.login("23CSE042", "student123")
        assertNotNull(sessionManager.currentUserId.first())

        authRepository.logout()
        assertNull(sessionManager.currentUserId.firstOrNull())
    }

    @Test
    fun `direct login with correct password succeeds and records LoginEvent`() = runTest {
        val result = authRepository.login("23CSE042", "student123")
        assertTrue(result.isSuccess)
        val user = result.getOrThrow()
        assertEquals("23CSE042", user.registerNumber)

        val currentUserId = sessionManager.currentUserId.first()
        assertEquals(user.id, currentUserId)

        // Verify LoginEvent recorded
        val events = database.loginEventDao().getAllLoginEvents().first()
        assertTrue(events.any { it.registerNumber == "23CSE042" && it.loginStatus == "SUCCESS" })
    }

    @Test
    fun `direct login with wrong password fails and does not set session`() = runTest {
        val result = authRepository.login("23CSE042", "wrong_password")
        assertTrue(result.isFailure)
        val msg = result.exceptionOrNull()?.message ?: ""
        assertTrue(msg.contains("Invalid") || msg.contains("password"))

        assertNull(sessionManager.currentUserId.firstOrNull())
    }

    @Test
    fun `direct login with unknown register number fails`() = runTest {
        val result = authRepository.login("UNKNOWN999", "pass123")
        assertTrue(result.isFailure)
        assertNull(sessionManager.currentUserId.firstOrNull())
    }

    @Test
    fun `direct login with pending account fails until approved`() = runTest {
        val pendingResult = authRepository.login("23CSE099", "welcome")
        assertTrue(pendingResult.isFailure)
        assertTrue(pendingResult.exceptionOrNull()?.message?.contains("awaiting") == true)

        // Admin approves
        authRepository.approveStudentAccount("user_admin", "user_new_student")

        // Now login succeeds
        val approvedResult = authRepository.login("23CSE099", "welcome")
        assertTrue(approvedResult.isSuccess)
    }

    @Test
    fun `direct login with suspended account fails`() = runTest {
        val result = authRepository.login("23CSE088", "pass123")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("suspended") == true)
    }

    @Test
    fun `direct login with rejected account fails`() = runTest {
        // Reject 23CSE099
        authRepository.rejectStudentAccount("user_admin", "user_new_student")
        val result = authRepository.login("23CSE099", "welcome")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("not authorized") == true)
    }

    @Test
    fun `admin user login succeeds when active`() = runTest {
        val result = authRepository.login("ADMIN01", "admin123")
        assertTrue(result.isSuccess)
        val adminUser = result.getOrThrow()
        assertEquals("ADMIN01", adminUser.registerNumber)
        assertTrue(adminUser.isAdmin)
    }

    @Test
    fun `session survives Activity recreation and app background`() = runTest {
        // 1. User logs in
        val loginResult = authRepository.login("23CSE001", "pass123")
        assertTrue(loginResult.isSuccess)
        val loggedInUser = authRepository.observeCurrentUser().first()
        assertNotNull(loggedInUser)
        assertEquals("23CSE001", loggedInUser?.registerNumber)

        // 2. Simulate Activity recreation (e.g. screen rotation or returning from Recent Apps / onStop)
        // A new repository instance is created as would happen when Activity/ViewModel is recreated
        val newRepositoryInstance = AuthRepository(
            userDao = database.userDao(),
            loginEventDao = database.loginEventDao(),
            sessionManager = sessionManager,
            authApi = mockAuthApi
        )

        // User remains logged in across instances within the process
        val currentUserAfterRecreation = newRepositoryInstance.observeCurrentUser().first()
        assertNotNull(currentUserAfterRecreation)
        assertEquals("23CSE001", currentUserAfterRecreation?.registerNumber)
    }

    @Test
    fun `restoreSessionIfValid restores active user from SessionManager`() = runTest {
        // 1. Pre-save a valid unexpired session in sessionManager for 23CSE001
        sessionManager.saveSession(
            accessToken = "token_23cse001",
            refreshToken = "refresh_23cse001",
            userId = "user_23cse001",
            registerNumber = "23CSE001",
            expiresInSeconds = 3600
        )
        // Reset in-process memory state
        AuthRepository.resetProcessState()
        assertNull(authRepository.observeCurrentUser().first())

        // 2. Restore session
        val restored = authRepository.restoreSessionIfValid()
        assertTrue(restored)

        // 3. User is restored and active
        val currentUser = authRepository.observeCurrentUser().first()
        assertNotNull(currentUser)
        assertEquals("23CSE001", currentUser?.registerNumber)
    }

    @Test
    fun `restoreSessionIfValid rejects and clears suspended account`() = runTest {
        // 1. Pre-save session for suspended user 23CSE088
        sessionManager.saveSession(
            accessToken = "token_23cse088",
            refreshToken = "refresh_23cse088",
            userId = "user_suspended_student",
            registerNumber = "23CSE088",
            expiresInSeconds = 3600
        )
        AuthRepository.resetProcessState()

        // 2. Attempt restore
        val restored = authRepository.restoreSessionIfValid()
        assertFalse(restored)

        // 3. Session is cleared and currentUser is null
        assertNull(authRepository.observeCurrentUser().first())
        assertNull(sessionManager.getSession())
    }

    @Test
    fun `logout completely clears session and prevents returning to home`() = runTest {
        // 1. Login
        val loginResult = authRepository.login("23CSE001", "pass123")
        assertTrue(loginResult.isSuccess)
        assertNotNull(authRepository.observeCurrentUser().first())
        assertNotNull(sessionManager.getSession())

        // 2. Logout
        authRepository.logout()

        // 3. Both in-process and persisted session are null
        assertNull(authRepository.observeCurrentUser().first())
        assertNull(sessionManager.getSession())

        // 4. Any subsequent check or new repository instance sees logged-out state
        val freshRepo = AuthRepository(
            userDao = database.userDao(),
            loginEventDao = database.loginEventDao(),
            sessionManager = sessionManager,
            authApi = mockAuthApi
        )
        assertNull(freshRepo.observeCurrentUser().first())
    }
}
