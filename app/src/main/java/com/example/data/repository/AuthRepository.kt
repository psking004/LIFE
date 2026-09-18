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
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
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

data class EmailOtpChallenge(
    val userId: String,
    val registerNumber: String,
    val maskedEmail: String,
    val expiresInSeconds: Int,
    val accessToken: String,
    val refreshToken: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class ResolvedIdentity(
    val userId: String,
    val registerNumber: String,
    val authEmail: String,
    val maskedEmail: String,
    val alreadyVerifiedToday: Boolean,
    val status: String,
    val isAdmin: Boolean
)

sealed class LoginStep {
    data class Authenticated(val user: User) : LoginStep()
    data class OtpRequired(val challenge: EmailOtpChallenge) : LoginStep()
}

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

        fun maskEmail(email: String): String {
            val atIdx = email.indexOf('@')
            if (atIdx <= 1) return "your registered email"
            val local = email.substring(0, atIdx)
            val domain = email.substring(atIdx)
            return if (local.length <= 2) {
                "${local.first()}***$domain"
            } else {
                "${local.first()}***${local.last()}$domain"
            }
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

        fun setCurrentProcessUser(userId: String) {
            _currentProcessUserId.value = userId
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
        return@withContext Result.failure(
            IllegalStateException("Legacy Supabase password-auth OTP flow is disabled. Use Firebase email/password login and the LIFE email OTP flow.")
        )
    }

    suspend fun verifyOtp(challenge: OtpChallenge, otpCode: String): Result<User> = withContext(dispatcher) {
        return@withContext Result.failure(
            IllegalStateException("Legacy SMS OTP verification is disabled. Use the LIFE email OTP flow.")
        )
    }

    /**
     * Resolves the authoritative Supabase Auth email and verification state for the Register Number.
     * Attempts server-side RPC / Edge Function first, and falls back to local database record.
     */
    suspend fun resolveIdentity(normalizedReg: String): Result<ResolvedIdentity> = withContext(dispatcher) {
        // 1. Check local database record first if available
        val localUser = userDao.getUserByRegisterNumber(normalizedReg)
        if (localUser != null) {
            when (localUser.accountStatus) {
                AccountStatus.PENDING.name -> return@withContext Result.failure(IllegalStateException("Your account is awaiting administrator approval."))
                AccountStatus.SUSPENDED.name -> return@withContext Result.failure(IllegalStateException("Your account has been suspended. Contact the administrator."))
                AccountStatus.REJECTED.name -> return@withContext Result.failure(IllegalStateException("Your account is not authorized."))
            }
            if (localUser.isSuspended) {
                return@withContext Result.failure(IllegalStateException("Your account has been suspended. Contact the administrator."))
            }
            if (localUser.email.isBlank()) {
                return@withContext Result.failure(IllegalStateException("Identity resolution failed: no Firebase email is registered for this account."))
            }
        }

        // 2. Attempt server-side RPC
        try {
            val rpcResp = authApi.resolveLoginIdentityRpc(mapOf("p_register_number" to normalizedReg))
            if (rpcResp.isSuccessful && rpcResp.body()?.ok == true) {
                val b = rpcResp.body()!!
                if (b.status in listOf("PENDING", "SUSPENDED", "REJECTED")) {
                    val errMsg = when (b.status) {
                        "PENDING" -> "Your account is awaiting administrator approval."
                        "SUSPENDED" -> "Your account has been suspended. Contact the administrator."
                        else -> "Your account is not authorized."
                    }
                    return@withContext Result.failure(IllegalStateException(errMsg))
                }
                if (b.authEmail.isNullOrBlank()) {
                    return@withContext Result.failure(IllegalStateException("Identity resolution failed: no Firebase email is registered for this account."))
                }
                return@withContext Result.success(
                    ResolvedIdentity(
                        userId = b.userId ?: (localUser?.id ?: "user_${normalizedReg.lowercase()}"),
                        registerNumber = b.registerNumber ?: normalizedReg,
                        authEmail = b.authEmail,
                        maskedEmail = b.maskedEmail ?: maskEmail(b.authEmail),
                        alreadyVerifiedToday = b.alreadyVerifiedToday,
                        status = b.status ?: "ACTIVE",
                        isAdmin = b.isAdmin
                    )
                )
            } else if (rpcResp.code() == 403 || rpcResp.body()?.status in listOf("PENDING", "SUSPENDED", "REJECTED")) {
                val err = rpcResp.body()?.error ?: "Account is not active."
                return@withContext Result.failure(IllegalStateException(err))
            }
        } catch (ignored: Exception) {
            // RPC not deployed or network down, try Edge Function
        }

        // 3. Attempt Edge Function
        try {
            val fnResp = authApi.resolveLoginIdentityFunction(mapOf("register_number" to normalizedReg))
            if (fnResp.isSuccessful && fnResp.body()?.ok == true) {
                val b = fnResp.body()!!
                if (b.status in listOf("PENDING", "SUSPENDED", "REJECTED")) {
                    val errMsg = when (b.status) {
                        "PENDING" -> "Your account is awaiting administrator approval."
                        "SUSPENDED" -> "Your account has been suspended. Contact the administrator."
                        else -> "Your account is not authorized."
                    }
                    return@withContext Result.failure(IllegalStateException(errMsg))
                }
                if (b.authEmail.isNullOrBlank()) {
                    return@withContext Result.failure(IllegalStateException("Identity resolution failed: no Firebase email is registered for this account."))
                }
                return@withContext Result.success(
                    ResolvedIdentity(
                        userId = b.userId ?: (localUser?.id ?: "user_${normalizedReg.lowercase()}"),
                        registerNumber = b.registerNumber ?: normalizedReg,
                        authEmail = b.authEmail,
                        maskedEmail = b.maskedEmail ?: maskEmail(b.authEmail),
                        alreadyVerifiedToday = b.alreadyVerifiedToday,
                        status = b.status ?: "ACTIVE",
                        isAdmin = b.isAdmin
                    )
                )
            } else if (fnResp.code() == 403 || fnResp.body()?.status in listOf("PENDING", "SUSPENDED", "REJECTED")) {
                val err = fnResp.body()?.error ?: "Account is not active."
                return@withContext Result.failure(IllegalStateException(err))
            }
        } catch (ignored: Exception) {
            // Edge function not reachable, proceed with local Room
        }

        // 4. Fallback to local Room database
        if (localUser == null) {
            return@withContext Result.failure(IllegalArgumentException("Account Not Found: We couldn't find a LIFE account with this register number."))
        }

        val authEmail = localUser.email
        if (authEmail.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Identity resolution failed: no Firebase email registered for this account."))
        }

        Result.success(
            ResolvedIdentity(
                userId = localUser.id,
                registerNumber = localUser.registerNumber,
                authEmail = authEmail,
                maskedEmail = maskEmail(authEmail),
                alreadyVerifiedToday = false,
                status = localUser.accountStatus,
                isAdmin = localUser.isAdmin
            )
        )
    }

    /**
     * Direct password-based login with daily email OTP verification.
     *
     * Flow:
     * 1. Register Number + Password validation
     * 2. Authoritative identity resolution & AccountStatus verification:
     *    - PENDING -> blocked
     *    - SUSPENDED -> blocked
     *    - REJECTED -> blocked
     *    - ACTIVE -> allowed
     * 3. Firebase email/password authentication using the real registered email
     * 4. Use Firebase ID token for the LIFE OTP backend
     * 5. Check daily OTP state via Edge Function (send-login-otp):
     *    - If alreadyVerifiedToday -> establish session & return LoginStep.Authenticated
     *    - If not verified -> send OTP to the registered email & return LoginStep.OtpRequired
     * 6. Record SUCCESS LoginEvent in audit trail
     */
    suspend fun login(registerNumber: String, password: String): Result<LoginStep> = withContext(dispatcher) {
        val normalizedReg = normalizeRegisterNumber(registerNumber)
        if (normalizedReg.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Please enter a valid Register Number"))
        }
        if (password.trim().isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Please enter your password"))
        }


        Log.d("LIFE_AUTH", "LOGIN_STARTED for $normalizedReg")

        // 1. Resolve Identity and verify status
        val identityRes = resolveIdentity(normalizedReg)
        if (identityRes.isFailure) {
            return@withContext Result.failure(
                identityRes.exceptionOrNull() ?: IllegalArgumentException("Invalid Register Number or Password")
            )
        }
        val identity = identityRes.getOrThrow()

        // 2. Firebase email/password authentication using the real registered email
        val accessToken: String
        val refreshToken: String
        val expiresIn: Long
        val supabaseUserId: String?

        try {
            val firebaseUser = firebaseEmailPasswordSignIn(identity.authEmail, password.trim())
            val firebaseToken = firebaseUser.getIdToken(false)
            val tokenResult = Tasks.await(firebaseToken)
            val firebaseIdToken = tokenResult.token
                ?: return@withContext Result.failure(IllegalStateException("Firebase authentication succeeded but no ID token was returned."))

            accessToken = firebaseIdToken
            refreshToken = ""
            expiresIn = 3600L
            supabaseUserId = firebaseUser.uid
            SupabaseClient.currentAccessToken.set(firebaseIdToken)
        } catch (e: Exception) {
            val category = when (e) {
                is FirebaseAuthInvalidCredentialsException -> "AUTH_INVALID_CREDENTIALS"
                is FirebaseAuthException -> if (e.errorCode == "ERROR_USER_DISABLED") "AUTH_USER_DISABLED" else "AUTH_CONFIGURATION_ERROR"
                is FirebaseException -> "AUTH_NETWORK_ERROR"
                else -> "AUTH_CONFIGURATION_ERROR"
            }
            if (com.example.BuildConfig.DEBUG) {
                Log.d("LIFE_AUTH_DIAGNOSTIC", "$category: ${identity.authEmail.take(32)}")
            }
            return@withContext Result.failure(IllegalStateException("Invalid Register Number or Password"))
        }

        // 3. Ensure local user profile exists and sync authUserId
        var localUser = userDao.getUserByRegisterNumber(normalizedReg)
        if (localUser == null) {
            val newEntity = UserEntity(
                id = identity.userId.ifEmpty { "user_${normalizedReg.lowercase()}" },
                authUserId = supabaseUserId,
                registerNumber = normalizedReg,
                email = identity.authEmail,
                emailVerified = true,
                accountStatus = AccountStatus.ACTIVE.name,
                isFirstLogin = false,
                isSuspended = false,
                isAdmin = identity.isAdmin
            )
            userDao.insertUser(newEntity)
            localUser = newEntity
        } else if (supabaseUserId != null && localUser.authUserId != supabaseUserId) {
            userDao.updateAuthUserId(localUser.id, supabaseUserId)
            localUser = localUser.copy(authUserId = supabaseUserId)
        }

        // 4. Daily OTP Verification Check
        if (identity.alreadyVerifiedToday) {
            _currentProcessUserId.value = localUser.id
            SupabaseClient.currentAccessToken.set(accessToken)
            sessionManager.saveSession(
                accessToken = accessToken,
                refreshToken = refreshToken,
                userId = localUser.id,
                registerNumber = localUser.registerNumber,
                expiresInSeconds = expiresIn
            )
            recordLoginEvent(localUser, "SUCCESS", accessToken)
            return@withContext Result.success(LoginStep.Authenticated(localUser.toDomain()))
        }

        // Request Daily Login OTP from Supabase Edge Function
        try {
            val otpResponse = authApi.sendLoginOtp("Bearer $accessToken")
            if (otpResponse.isSuccessful && otpResponse.body() != null) {
                val otpBody = otpResponse.body()!!
                if (otpBody.alreadyVerifiedToday) {
                    _currentProcessUserId.value = localUser.id
                    SupabaseClient.currentAccessToken.set(accessToken)
                    sessionManager.saveSession(
                        accessToken = accessToken,
                        refreshToken = refreshToken,
                        userId = localUser.id,
                        registerNumber = localUser.registerNumber,
                        expiresInSeconds = expiresIn
                    )
                    recordLoginEvent(localUser, "SUCCESS", accessToken)
                    return@withContext Result.success(LoginStep.Authenticated(localUser.toDomain()))
                } else {
                    // Daily OTP required; do NOT save persistent session yet
                    return@withContext Result.success(
                        LoginStep.OtpRequired(
                            EmailOtpChallenge(
                                userId = localUser.id,
                                registerNumber = localUser.registerNumber,
                                maskedEmail = otpBody.emailMasked ?: identity.maskedEmail,
                                expiresInSeconds = otpBody.expiresInSeconds,
                                accessToken = accessToken,
                                refreshToken = refreshToken
                            )
                        )
                    )
                }
            } else {
                if (com.example.BuildConfig.DEBUG) {
                    Log.d("LIFE_AUTH_DIAGNOSTIC", "OTP_REQUEST_FAILED: ${otpResponse.code()}")
                }
                return@withContext Result.failure(IllegalStateException("Failed to request email OTP: ${otpResponse.code()}"))
            }
        } catch (e: Exception) {
            if (com.example.BuildConfig.DEBUG) {
                Log.d("LIFE_AUTH_DIAGNOSTIC", "OTP_REQUEST_FAILED: ${e.javaClass.simpleName}")
            }
            return@withContext Result.failure(IllegalStateException("Network error requesting email OTP"))
        }
    }

    private suspend fun firebaseEmailPasswordSignIn(email: String, password: String): FirebaseUser {
        val auth = FirebaseAuth.getInstance()
        val result = Tasks.await(auth.signInWithEmailAndPassword(email, password))
        return result.user
            ?: throw IllegalStateException("Firebase authentication succeeded but no user was returned.")
    }

    fun isAdminAccessAllowed(user: UserEntity?): Boolean {
        return user != null && user.isAdmin && !user.isSuspended && user.accountStatus == AccountStatus.ACTIVE.name
    }

    suspend fun adminLogin(email: String, password: String): Result<User> = withContext(dispatcher) {
        val trimmedEmail = email.trim()
        if (trimmedEmail.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Please enter your admin email"))
        }
        if (password.trim().isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Please enter your password"))
        }

        val auth = FirebaseAuth.getInstance()
        try {
            val firebaseUser = firebaseEmailPasswordSignIn(trimmedEmail, password.trim())
            val tokenResult = Tasks.await(firebaseUser.getIdToken(false))
            val idToken = tokenResult.token ?: return@withContext Result.failure(IllegalStateException("Failed to validate admin identity"))

            try {
                val userResp = authApi.getUser("Bearer $idToken")
                if (!userResp.isSuccessful || userResp.body() == null) {
                    auth.signOut()
                    return@withContext Result.failure(IllegalStateException("Admin access denied"))
                }
            } catch (_: Exception) {
                auth.signOut()
                return@withContext Result.failure(IllegalStateException("Admin access denied"))
            }

            val allUsers = userDao.getAllUsers().firstOrNull() ?: emptyList()
            val matchingUser = allUsers.firstOrNull {
                it.email.equals(trimmedEmail, ignoreCase = true) || it.authUserId == firebaseUser.uid
            } ?: run {
                auth.signOut()
                return@withContext Result.failure(IllegalStateException("Admin access denied"))
            }

            if (!isAdminAccessAllowed(matchingUser)) {
                auth.signOut()
                val reason = when (matchingUser.accountStatus) {
                    AccountStatus.PENDING.name -> "Admin account is pending approval."
                    AccountStatus.SUSPENDED.name -> "Admin account is suspended."
                    AccountStatus.REJECTED.name -> "Admin account is not authorized."
                    else -> "Admin access denied."
                }
                return@withContext Result.failure(IllegalStateException(reason))
            }

            val user = matchingUser.toDomain()
            _currentProcessUserId.value = user.id
            SupabaseClient.currentAccessToken.set(idToken)
            sessionManager.saveSession(
                accessToken = idToken,
                refreshToken = "",
                userId = user.id,
                registerNumber = user.registerNumber,
                expiresInSeconds = 3600L
            )
            recordLoginEvent(matchingUser, "SUCCESS", idToken)
            return@withContext Result.success(user)
        } catch (e: Exception) {
            auth.signOut()
            val msg = when (e) {
                is FirebaseAuthInvalidCredentialsException -> "Invalid admin email or password"
                is FirebaseAuthException -> "Admin authentication failed"
                is FirebaseException -> "Network error during admin authentication"
                else -> e.message ?: "Admin login failed"
            }
            return@withContext Result.failure(IllegalStateException(msg))
        }
    }

    suspend fun verifyEmailOtp(challenge: EmailOtpChallenge, otpCode: String): Result<User> = withContext(dispatcher) {
        val cleanOtp = otpCode.trim()
        if (cleanOtp.length != 6 || !cleanOtp.all { it.isDigit() }) {
            return@withContext Result.failure(IllegalArgumentException("Invalid OTP. Please enter a 6-digit code."))
        }

        try {
            val verifyRes = authApi.verifyLoginOtp(
                "Bearer ${challenge.accessToken}",
                mapOf("otp" to cleanOtp)
            )

            if (verifyRes.isSuccessful && verifyRes.body()?.ok == true) {
                // Verified successfully
                val userEntity = userDao.getUserById(challenge.userId)
                    ?: return@withContext Result.failure(IllegalStateException("User profile not found"))

                _currentProcessUserId.value = userEntity.id
                SupabaseClient.currentAccessToken.set(challenge.accessToken)

                sessionManager.saveSession(
                    accessToken = challenge.accessToken,
                    refreshToken = challenge.refreshToken,
                    userId = userEntity.id,
                    registerNumber = userEntity.registerNumber,
                    expiresInSeconds = challenge.expiresInSeconds.toLong()
                )

                recordLoginEvent(userEntity, "SUCCESS", challenge.accessToken)
                return@withContext Result.success(userEntity.toDomain())
            } else {
                val errorMsg = verifyRes.body()?.error ?: "Invalid OTP"
                return@withContext Result.failure(IllegalArgumentException(errorMsg))
            }
        } catch (e: Exception) {
            return@withContext Result.failure(IllegalStateException("Network error during OTP verification"))
        }
    }

    private suspend fun recordLoginEvent(user: UserEntity, status: String, sessionId: String) {
        try {
            val loginEvent = LoginEventEntity(
                id = UUID.randomUUID().toString(),
                userId = user.id,
                registerNumber = user.registerNumber,
                userName = user.name.ifEmpty { "Student ${user.registerNumber}" },
                loginAt = System.currentTimeMillis(),
                loginStatus = status,
                deviceType = "Android",
                appVersion = "1.0.0",
                sessionId = sessionId
            )
            loginEventDao.insertLoginEvent(loginEvent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record login event: ${e.message}")
        }
    }

    /**
     * First-login password setup.
     * This updates the password in Firebase Auth (client), then notifies the server
     * to clear `is_first_login` for the associated Supabase user.
     * Never store password or its hash locally.
     */
    suspend fun setupFirstLoginPassword(userId: String, newPassword: String): Result<Unit> = withContext(dispatcher) {
        if (newPassword.trim().length < 6) {
            return@withContext Result.failure(IllegalArgumentException("Password must be at least 6 characters"))
        }

        try {
            val auth = FirebaseAuth.getInstance()
            val currentUser = auth.currentUser ?: return@withContext Result.failure(IllegalStateException("No authenticated Firebase user found"))

            // Update Firebase password for the currently signed-in user
            val updateTask = currentUser.updatePassword(newPassword.trim())
            Tasks.await(updateTask)

            // Obtain a fresh ID token to call server-side RPC
            val tokenResult = Tasks.await(currentUser.getIdToken(false))
            val idToken = tokenResult.token ?: return@withContext Result.failure(IllegalStateException("Failed to obtain Firebase ID token"))

            // Call server-side function to mark first-login complete
            try {
                val resp = authApi.completeFirstLogin("Bearer $idToken")
                if (resp.isSuccessful && resp.body()?.ok == true) {
                    Log.i(TAG, "First-login completed server-side for Firebase UID: ${currentUser.uid}")
                    return@withContext Result.success(Unit)
                } else {
                    return@withContext Result.failure(IllegalStateException("Failed to mark first-login complete on server"))
                }
            } catch (e: Exception) {
                return@withContext Result.failure(IllegalStateException("Network error while finalizing first login"))
            }
        } catch (e: Exception) {
            return@withContext Result.failure(IllegalStateException("Failed to update Firebase password: ${e.message}"))
        }
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
        initialPassword: String = "pass123",
        email: String = ""
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

        val studentEmail = email.trim()
        if (studentEmail.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("A real Firebase email is required for student account creation."))
        }
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(studentEmail).matches()) {
            return@withContext Result.failure(IllegalArgumentException("Please enter a valid email address."))
        }
        val lowerEmail = studentEmail.lowercase()
        if (lowerEmail.endsWith("@life.edu") || lowerEmail.endsWith("@campus.edu")) {
            return@withContext Result.failure(IllegalArgumentException("Use the student's real email address, not a synthetic campus email."))
        }
        var authUserId: String? = null
        val adminToken = SupabaseClient.currentAccessToken.get()

        if (!adminToken.isNullOrBlank()) {
            try {
                val resp = authApi.adminManageUser(
                    "Bearer $adminToken",
                    mapOf(
                        "action" to "create_student",
                        "register_number" to normalizedReg,
                        "name" to name.trim(),
                        "email" to studentEmail,
                        "phone_number" to phoneNumber.trim(),
                        "department" to department.trim(),
                        "initial_password" to initialPassword.trim()
                    )
                )
                if (resp.isSuccessful && resp.body()?.ok == true) {
                    authUserId = resp.body()?.userId
                }
            } catch (e: Exception) {
                Log.w(TAG, "Edge function student creation skipped: ${e.message}")
            }
        }

        val userId = "user_" + normalizedReg.lowercase()
        val entity = UserEntity(
            id = userId,
            authUserId = authUserId,
            registerNumber = normalizedReg,
            name = name.trim().ifEmpty { "Student $normalizedReg" },
            phoneNumber = phoneNumber.trim().ifEmpty { "9876541234" },
            department = department.trim().ifEmpty { "Computer Science & Engineering" },
            email = studentEmail,
            emailVerified = true,
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

    /**
     * Self-service student signup. Creates Firebase user server-side and inserts PENDING LIFE user.
     */
    suspend fun registerStudent(
        registerNumber: String,
        password: String,
        email: String,
        phoneNumber: String,
        name: String = "",
        department: String = "Computer Science & Engineering"
    ): Result<User> = withContext(dispatcher) {
        val normalizedReg = normalizeRegisterNumber(registerNumber)
        if (normalizedReg.isEmpty()) return@withContext Result.failure(IllegalArgumentException("Register number cannot be empty"))

        if (email.trim().isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) {
            return@withContext Result.failure(IllegalArgumentException("A valid email is required"))
        }

        try {
            val resp = authApi.selfSignUp(
                mapOf(
                    "register_number" to normalizedReg,
                    "initial_password" to password.trim(),
                    "email" to email.trim(),
                    "phone_number" to phoneNumber.trim(),
                    "name" to name.trim(),
                    "department" to department.trim()
                )
            )

            if (resp.isSuccessful && resp.body()?.ok == true) {
                val userId = resp.body()?.userId ?: "user_${normalizedReg.lowercase()}"
                val entity = UserEntity(
                    id = userId,
                    authUserId = resp.body()?.userId,
                    registerNumber = normalizedReg,
                    name = name.trim().ifEmpty { "Student $normalizedReg" },
                    phoneNumber = phoneNumber.trim(),
                    department = department.trim().ifEmpty { "Computer Science & Engineering" },
                    email = email.trim(),
                    emailVerified = true,
                    accountStatus = AccountStatus.PENDING.name,
                    isFirstLogin = true,
                    isSuspended = false,
                    isAdmin = false,
                    createdAt = System.currentTimeMillis()
                )
                userDao.insertUser(entity)
                return@withContext Result.success(entity.toDomain())
            } else {
                val err = resp.body()?.error?.takeIf { it.isNotBlank() }
                    ?: "Unable to create account. Please try again."
                return@withContext Result.failure(IllegalStateException(err))
            }
        } catch (e: Exception) {
            return@withContext Result.failure(IllegalStateException("Unable to create account. Please try again."))
        }
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
