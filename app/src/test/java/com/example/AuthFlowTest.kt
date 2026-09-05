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
            sessionManager = sessionManager
        )
        repository = LifeRepository(database, sessionManager)

        // Preload seed users
        runTest {
            database.userDao().insertUsers(SeedData.initialUsers)
        }
    }

    @After
    fun tearDown() {
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
}
