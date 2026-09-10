package com.example.data.repository

import android.util.Log
import com.example.data.local.dao.LoginEventDao
import com.example.data.local.dao.UserDao
import com.example.data.local.entities.LoginEventEntity
import com.example.data.local.entities.UserEntity
import com.example.data.remote.SupabaseClient
import com.example.data.remote.api.SupabaseAuthApi
import com.example.domain.model.AccountStatus
import com.example.domain.model.LoginEvent
import com.example.domain.model.User
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

data class OtpChallenge(
    val userId: String,
    val registerNumber: String,
    val maskedPhoneNumber: String,
    val fullPhoneNumber: String,
    val email: String,
    val factorId: String,
    val challengeId: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Repository responsible for Supabase GoTrue authentication, deterministic
 * register-number-to-identity mapping, per-process session lifecycle, OTP verification,
 * login activity audit trails, and admin-authorized account management.
 */
class AuthRepository(
    private val userDao: UserDao,
    private val loginEventDao: LoginEventDao,
    private val sessionManager: AuthSessionManager,
    private val authApi: SupabaseAuthApi = SupabaseClient.authApi,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    companion object {
        private const val TAG = "LifeAuth"

        // In-memory process-level active user ID: survives Activity lifecycle, onPause, onStop, Recent Apps, and rotation
        private val _currentProcessUserId = MutableStateFlow<String?>(null)

        fun normalizeRegisterNumber(raw: String): String {
            return raw.trim().uppercase()
        }

        fun mapToAuthEmail(normalizedReg: String): String {
            return "${normalizedReg.trim().lowercase()}@life.edu"
        }

        fun maskPhoneNumber(raw: String): String {
            val clean = raw.filter { it.isDigit() }
            val last4 = if (clean.length >= 4) clean.takeLast(4) else clean.padStart(4, '0')
            return "******$last4"
        }

        /**
         * For test resets and explicit session clearing.
         */
        fun resetProcessState() {
            _currentProcessUserId.value = null
        }
    }

    val currentUserId: Flow<String?> = _currentProcessUserId

    fun observeCurrentUser(): Flow<User?> {
        return _currentProcessUserId.combine(userDao.getAllUsers()) { userId, users ->
            if (userId == null) null else users.find { it.id == userId }?.toDomain()
        }.flowOn(dispatcher)
    }

    /**
     * Restores session if an existing valid session exists in AuthSessionManager
     * and the account is ACTIVE and not suspended.
     */
    suspend fun restoreSessionIfValid(): Boolean = withContext(dispatcher) {
        // If already set in process memory, verify user validity
        val currentId = _currentProcessUserId.value
        if (currentId != null) {
            val user = userDao.getUserById(currentId)
            if (user == null || user.accountStatus != AccountStatus.ACTIVE.name || user.isSuspended) {
                logout()
                return@withContext false
            }
            return@withContext true
        }

        // Try restoring from persisted session
        val session = sessionManager.getSession() ?: return@withContext false
        val user = userDao.getUserById(session.userId)
        if (user == null || user.accountStatus != AccountStatus.ACTIVE.name || user.isSuspended) {
            sessionManager.clearSession()
            _currentProcessUserId.value = null
            SupabaseClient.currentAccessToken.set(null)
            return@withContext false
        }

        if (session.expiresAt > System.currentTimeMillis()) {
            _currentProcessUserId.value = session.userId
            SupabaseClient.currentAccessToken.set(session.accessToken)
            Log.i(TAG, "Restored active session for ${user.registerNumber}")
            return@withContext true
        } else if (session.refreshToken.isNotBlank()) {
            return@withContext refreshSessionIfNeeded()
        } else {
            sessionManager.clearSession()
            _currentProcessUserId.value = null
            SupabaseClient.currentAccessToken.set(null)
            return@withContext false
        }
    }

    /**
     * Refreshes the Supabase token using refreshToken when nearing expiration or expired.
     */
    suspend fun refreshSessionIfNeeded(): Boolean = withContext(dispatcher) {
        val session = sessionManager.getSession() ?: return@withContext false
        if (session.expiresAt > System.currentTimeMillis() + 60_000) {
            return@withContext true
        }
        if (session.refreshToken.isBlank()) {
            logout()
            return@withContext false
        }
        try {
            val response = authApi.refreshToken(mapOf("refresh_token" to session.refreshToken))
            if (response.isSuccessful && response.body()?.accessToken != null) {
                val body = response.body()!!
                val newAccess = body.accessToken!!
                val newRefresh = body.refreshToken ?: session.refreshToken
                val expiresIn = body.expiresIn ?: 3600L
                sessionManager.updateTokens(newAccess, newRefresh, expiresIn)
                SupabaseClient.currentAccessToken.set(newAccess)
                _currentProcessUserId.value = session.userId
                Log.i(TAG, "Supabase session refreshed successfully")
                true
            } else {
                logout()
                false
            }
        } catch (e: Exception) {
            if (session.expiresAt <= System.currentTimeMillis()) {
                logout()
                false
            } else {
                true
            }
        }
    }

    fun observeAllLoginEvents(): Flow<List<LoginEvent>> {
        return loginEventDao.getAllLoginEvents().map { entities ->
            entities.map { it.toDomain() }
        }.flowOn(dispatcher)
    }

    fun observeRecentSuccessfulLogins(): Flow<List<LoginEvent>> {
        return loginEventDao.getRecentSuccessfulLogins().map { entities ->
            entities.map { it.toDomain() }
        }.flowOn(dispatcher)
    }

    /**
     * Step 1 & 2: Account Authorization Check and Password Verification.
     * Generates and dispatches a real OTP to the student's registered mobile number.
     */
        suspend fun requestLoginOtp(registerNumber: String, password: String): Result<OtpChallenge> = withContext(dispatcher) {
        val normalizedReg = normalizeRegisterNumber(registerNumber)
        if (normalizedReg.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Please enter a valid Register Number"))
        }
        if (password.trim().isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Please enter your password"))
        }

        Log.d("LIFE_AUTH", "LOGIN_STARTED")
        Log.i(TAG, "Step 1: Account status lookup for $normalizedReg")

        // 1. Account Check
        val userEntity = userDao.getUserByRegisterNumber(normalizedReg)
        if (userEntity == null) {
            return@withContext Result.failure(IllegalArgumentException("Invalid Register Number or Password"))
        }

        val domainUser = userEntity.toDomain()
        when (domainUser.accountStatus) {
            AccountStatus.PENDING -> return@withContext Result.failure(IllegalStateException("Your account is awaiting administrator approval."))
            AccountStatus.SUSPENDED -> return@withContext Result.failure(IllegalStateException("Your account has been suspended. Contact the administrator."))
            AccountStatus.REJECTED -> return@withContext Result.failure(IllegalStateException("Your account is not authorized."))
            AccountStatus.ACTIVE -> { /* Proceed */ }
        }

        // 2. Password Verification via Supabase
        val authEmail = mapToAuthEmail(normalizedReg)
        var accessToken = ""
        
        try {
            val response = authApi.signInWithPassword(
                mapOf("email" to authEmail, "password" to password.trim())
            )
            if (response.isSuccessful && response.body()?.accessToken != null) {
                accessToken = response.body()!!.accessToken!!
                SupabaseClient.currentAccessToken.set(accessToken)
                
                // Set authUserId on the local UserEntity if missing
                val supabaseUserId = response.body()?.user?.id
                if (supabaseUserId != null && userEntity.authUserId != supabaseUserId) {
                    userDao.updateAuthUserId(userEntity.id, supabaseUserId)
                }
            } else {
                return@withContext Result.failure(IllegalArgumentException("Invalid Register Number or Password"))
            }
        } catch (e: Exception) {
            return@withContext Result.failure(IllegalStateException("Network error connecting to authentication server"))
        }

        // 3. MFA Factor Enrollment or Verification
        val phone = domainUser.phoneNumber.ifEmpty { "9876541234" }
        val masked = maskPhoneNumber(phone)
        var factorId = ""

        try {
            // First check if they already have a phone factor
            val userResp = authApi.getUser("Bearer $accessToken")
            val factors = userResp.body()?.factors ?: emptyList()
            val phoneFactor = factors.find { it.factorType == "phone" && it.status == "verified" }
            
            if (phoneFactor != null) {
                factorId = phoneFactor.id
            } else {
                // Enroll new phone factor
                val enrollResp = authApi.enrollFactor("Bearer $accessToken", mapOf(
                    "factor_type" to "phone",
                    "phone" to phone
                ))
                if (enrollResp.isSuccessful && enrollResp.body() != null) {
                    factorId = enrollResp.body()!!.id
                } else {
                    return@withContext Result.failure(IllegalStateException("Failed to enroll MFA factor"))
                }
            }
            
            // Create challenge (sends SMS)
            val challengeResp = authApi.createChallenge("Bearer $accessToken", factorId)
            if (challengeResp.isSuccessful && challengeResp.body() != null) {
                val challengeId = challengeResp.body()!!.id
                return@withContext Result.success(OtpChallenge(
                    userId = userEntity.id,
                    registerNumber = normalizedReg,
                    maskedPhoneNumber = masked,
                    fullPhoneNumber = phone,
                    email = authEmail,
                    factorId = factorId,
                    challengeId = challengeId,
                    timestamp = System.currentTimeMillis()
                ))
            } else {
                return@withContext Result.failure(IllegalStateException("Failed to create MFA challenge"))
            }
        } catch (e: Exception) {
            return@withContext Result.failure(IllegalStateException("Network error configuring MFA"))
        }
    }

    suspend fun verifyOtp(challenge: OtpChallenge, otpCode: String): Result<User> = withContext(dispatcher) {
        val cleanOtp = otpCode.trim()
        if (cleanOtp.length != 6 || !cleanOtp.all { it.isDigit() }) {
            return@withContext Result.failure(IllegalArgumentException("Invalid OTP. Please enter a 6-digit code."))
        }

        val elapsed = System.currentTimeMillis() - challenge.timestamp
        if (elapsed > 5 * 60 * 1000) {
            return@withContext Result.failure(IllegalStateException("OTP expired. Please request a new code."))
        }

        val token = SupabaseClient.currentAccessToken.get() ?: return@withContext Result.failure(IllegalStateException("Missing auth token"))

        try {
            val verifyRes = authApi.verifyFactor(
                "Bearer $token", 
                challenge.factorId,
                mapOf(
                    "challenge_id" to challenge.challengeId,
                    "code" to cleanOtp
                )
            )
            
            if (verifyRes.isSuccessful && verifyRes.body()?.accessToken != null) {
                val newAccessToken = verifyRes.body()!!.accessToken!!
                val newRefreshToken = verifyRes.body()!!.refreshToken ?: ""
                SupabaseClient.currentAccessToken.set(newAccessToken)
                
                // Session is now AAL2
                val userEntity = userDao.getUserById(challenge.userId)
                    ?: return@withContext Result.failure(IllegalStateException("User profile not found"))

                _currentProcessUserId.value = userEntity.id
                
                sessionManager.saveSession(
                    accessToken = newAccessToken,
                    refreshToken = newRefreshToken,
                    userId = userEntity.id,
                    registerNumber = userEntity.registerNumber,
                    expiresInSeconds = verifyRes.body()!!.expiresIn ?: 3600
                )

                try {
                    val loginEvent = LoginEventEntity(
                        id = UUID.randomUUID().toString(),
                        userId = userEntity.id,
                        registerNumber = userEntity.registerNumber,
                        userName = userEntity.name.ifEmpty { "Student ${userEntity.registerNumber}" },
                        loginAt = System.currentTimeMillis(),
                        loginStatus = "SUCCESS",
                        deviceType = "Android",
                        appVersion = "1.0.0",
                        sessionId = newAccessToken // Or parse JWT for session_id
                    )
                    loginEventDao.insertLoginEvent(loginEvent)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to record login event: ${e.message}")
                }

                return@withContext Result.success(userEntity.toDomain())
            } else {
                return@withContext Result.failure(IllegalArgumentException("Invalid OTP"))
            }
        } catch (e: Exception) {
            return@withContext Result.failure(IllegalStateException("Network error during OTP verification"))
        }
    }

    /**
     * Direct password-based login (OTP step temporarily disabled).
     *
     * Flow:
     * 1. Register Number + Password validation
     * 2. Verify account exists in local database (Admin created/authorized accounts only; no self-registration)
     * 3. Check AccountStatus:
     *    - PENDING -> blocked
     *    - SUSPENDED -> blocked
     *    - REJECTED -> blocked
     *    - ACTIVE -> allowed
     * 4. Real password verification using configured Supabase Auth backend (signInWithPassword)
     * 5. Capture real Supabase accessToken, refreshToken, and authUserId
     * 6. Establish in-memory process session and persist tokens
     * 7. Record SUCCESS LoginEvent in audit trail
     */
    suspend fun login(registerNumber: String, password: String): Result<User> = withContext(dispatcher) {
        val normalizedReg = normalizeRegisterNumber(registerNumber)
        if (normalizedReg.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Please enter a valid Register Number"))
        }
        if (password.trim().isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Please enter your password"))
        }

        Log.d("LIFE_AUTH", "DIRECT_LOGIN_STARTED for $normalizedReg")

        // 1. Verify account exists in local database (Admin-created/authorized accounts only)
        val userEntity = userDao.getUserByRegisterNumber(normalizedReg)
            ?: return@withContext Result.failure(IllegalArgumentException("Invalid Register Number or Password"))

        val domainUser = userEntity.toDomain()

        // 2. Verify account status
        when (domainUser.accountStatus) {
            AccountStatus.PENDING -> return@withContext Result.failure(IllegalStateException("Your account is awaiting administrator approval."))
            AccountStatus.SUSPENDED -> return@withContext Result.failure(IllegalStateException("Your account has been suspended. Contact the administrator."))
            AccountStatus.REJECTED -> return@withContext Result.failure(IllegalStateException("Your account is not authorized."))
            AccountStatus.ACTIVE -> {
                if (domainUser.isSuspended) {
                    return@withContext Result.failure(IllegalStateException("Your account has been suspended. Contact the administrator."))
                }
                // Proceed
            }
        }

        // 3. Password Verification via configured real Supabase Auth backend
        val authEmail = mapToAuthEmail(normalizedReg)
        val accessToken: String
        val refreshToken: String
        val expiresIn: Long

        try {
            val response = authApi.signInWithPassword(
                mapOf("email" to authEmail, "password" to password.trim())
            )
            if (response.isSuccessful && response.body()?.accessToken != null) {
                val body = response.body()!!
                accessToken = body.accessToken!!
                refreshToken = body.refreshToken ?: ""
                expiresIn = body.expiresIn ?: 3600L
                SupabaseClient.currentAccessToken.set(accessToken)

                // Sync authUserId on local UserEntity from Supabase Auth user ID
                val supabaseUserId = body.user?.id
                if (supabaseUserId != null && userEntity.authUserId != supabaseUserId) {
                    userDao.updateAuthUserId(userEntity.id, supabaseUserId)
                }
            } else {
                return@withContext Result.failure(IllegalArgumentException("Invalid Register Number or Password"))
            }
        } catch (e: Exception) {
            return@withContext Result.failure(IllegalStateException("Network error connecting to authentication server"))
        }

        // 4. Establish Session (Per-process in-memory session + DataStore token storage)
        _currentProcessUserId.value = userEntity.id

        sessionManager.saveSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            userId = userEntity.id,
            registerNumber = userEntity.registerNumber,
            expiresInSeconds = expiresIn
        )

        // 5. Record Login Event in local audit trail (SUCCESS recorded after password auth & ACTIVE status)
        try {
            val loginEvent = LoginEventEntity(
                id = UUID.randomUUID().toString(),
                userId = userEntity.id,
                registerNumber = userEntity.registerNumber,
                userName = userEntity.name.ifEmpty { "Student ${userEntity.registerNumber}" },
                loginAt = System.currentTimeMillis(),
                loginStatus = "SUCCESS",
                deviceType = "Android",
                appVersion = "1.0.0",
                sessionId = accessToken
            )
            loginEventDao.insertLoginEvent(loginEvent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record login event: ${e.message}")
        }

        Result.success(userEntity.toDomain())
    }

    /**
     * First-login permanent password setup.
     */
    suspend fun setupFirstLoginPassword(userId: String, newPassword: String): Result<Unit> = withContext(dispatcher) {
        if (newPassword.trim().length < 6) {
            return@withContext Result.failure(IllegalArgumentException("Password must be at least 6 characters"))
        }

        userDao.updatePassword(userId, newPassword.trim())
        Log.i(TAG, "First-login password updated for ID: $userId")
        Result.success(Unit)
    }

    /**
     * Ends the authentication session, clears in-memory and local session states.
     */
    suspend fun logout() = withContext(dispatcher) {
        Log.d("LIFE_AUTH", "LOGOUT")
        _currentProcessUserId.value = null
        val token = SupabaseClient.currentAccessToken.get()
        if (!token.isNullOrBlank() && !token.startsWith("local_auth_") && !token.startsWith("auth_")) {
            try {
                authApi.logout("Bearer $token")
            } catch (ignored: Exception) {}
        }
        SupabaseClient.currentAccessToken.set(null)
        sessionManager.clearSession()
        Log.i(TAG, "User logged out, session terminated")
    }

    // --- Admin Student Management Operations ---

    /**
     * Admin creates a student account. Account starts as PENDING.
     */
    suspend fun createStudentAccount(
        adminUserId: String,
        name: String,
        registerNumber: String,
        phoneNumber: String,
        department: String,
        initialPassword: String = "pass123"
    ): Result<User> = withContext(dispatcher) {
        val admin = userDao.getUserById(adminUserId)
        if (admin == null || !admin.isAdmin) {
            return@withContext Result.failure(IllegalStateException("Unauthorized: Admin privileges required"))
        }

        val normalizedReg = normalizeRegisterNumber(registerNumber)
        if (normalizedReg.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Register number cannot be empty"))
        }

        val existing = userDao.getUserByRegisterNumber(normalizedReg)
        if (existing != null) {
            return@withContext Result.failure(IllegalArgumentException("Account with register number $normalizedReg already exists"))
        }

        val userId = "user_" + normalizedReg.lowercase()
        val entity = UserEntity(
            id = userId,
            registerNumber = normalizedReg,
            passwordHash = initialPassword.trim(),
            name = name.trim().ifEmpty { "Student $normalizedReg" },
            phoneNumber = phoneNumber.trim().ifEmpty { "9876541234" },
            department = department.trim().ifEmpty { "Computer Science & Engineering" },
            accountStatus = AccountStatus.PENDING.name,
            isFirstLogin = true,
            isSuspended = false,
            isAdmin = false,
            createdAt = System.currentTimeMillis()
        )
        userDao.insertUser(entity)
        Log.i(TAG, "Admin ${admin.registerNumber} created PENDING student account for $normalizedReg")
        Result.success(entity.toDomain())
    }

    suspend fun approveStudentAccount(adminUserId: String, targetUserId: String): Result<Unit> = withContext(dispatcher) {
        val admin = userDao.getUserById(adminUserId)
        if (admin == null || !admin.isAdmin) {
            return@withContext Result.failure(IllegalStateException("Unauthorized: Admin privileges required"))
        }
        val target = userDao.getUserById(targetUserId) ?: return@withContext Result.failure(IllegalArgumentException("Student not found"))
        userDao.updateAccountStatus(
            id = targetUserId,
            status = AccountStatus.ACTIVE.name,
            isSuspended = false,
            approvedAt = System.currentTimeMillis(),
            approvedBy = admin.registerNumber
        )
        Log.i(TAG, "Admin ${admin.registerNumber} APPROVED account for ${target.registerNumber}")
        Result.success(Unit)
    }

    suspend fun rejectStudentAccount(adminUserId: String, targetUserId: String): Result<Unit> = withContext(dispatcher) {
        val admin = userDao.getUserById(adminUserId)
        if (admin == null || !admin.isAdmin) {
            return@withContext Result.failure(IllegalStateException("Unauthorized: Admin privileges required"))
        }
        val target = userDao.getUserById(targetUserId) ?: return@withContext Result.failure(IllegalArgumentException("Student not found"))
        userDao.updateAccountStatus(
            id = targetUserId,
            status = AccountStatus.REJECTED.name,
            isSuspended = false
        )
        Log.i(TAG, "Admin ${admin.registerNumber} REJECTED account for ${target.registerNumber}")
        Result.success(Unit)
    }

    suspend fun suspendStudentAccount(adminUserId: String, targetUserId: String): Result<Unit> = withContext(dispatcher) {
        val admin = userDao.getUserById(adminUserId)
        if (admin == null || !admin.isAdmin) {
            return@withContext Result.failure(IllegalStateException("Unauthorized: Admin privileges required"))
        }
        val target = userDao.getUserById(targetUserId) ?: return@withContext Result.failure(IllegalArgumentException("Student not found"))
        userDao.updateAccountStatus(
            id = targetUserId,
            status = AccountStatus.SUSPENDED.name,
            isSuspended = true
        )
        Log.i(TAG, "Admin ${admin.registerNumber} SUSPENDED account for ${target.registerNumber}")
        Result.success(Unit)
    }

    suspend fun restoreStudentAccount(adminUserId: String, targetUserId: String): Result<Unit> = withContext(dispatcher) {
        val admin = userDao.getUserById(adminUserId)
        if (admin == null || !admin.isAdmin) {
            return@withContext Result.failure(IllegalStateException("Unauthorized: Admin privileges required"))
        }
        val target = userDao.getUserById(targetUserId) ?: return@withContext Result.failure(IllegalArgumentException("Student not found"))
        userDao.updateAccountStatus(
            id = targetUserId,
            status = AccountStatus.ACTIVE.name,
            isSuspended = false
        )
        Log.i(TAG, "Admin ${admin.registerNumber} RESTORED account for ${target.registerNumber}")
        Result.success(Unit)
    }
}
