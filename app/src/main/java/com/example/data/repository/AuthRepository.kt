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

interface FirebaseAuthProvider {
    suspend fun signInWithEmailAndPassword(email: String, password: String): String
    fun getCurrentUid(): String?
    suspend fun getFreshAdminToken(): String?
    suspend fun updatePassword(newPassword: String)
    fun signOut()
}

class DefaultFirebaseAuthProvider : FirebaseAuthProvider {
    override suspend fun signInWithEmailAndPassword(email: String, password: String): String {
        val auth = FirebaseAuth.getInstance()
        val result = Tasks.await(auth.signInWithEmailAndPassword(email, password))
        return result.user?.uid ?: throw IllegalStateException("Firebase authentication succeeded but no user was returned.")
    }

    override fun getCurrentUid(): String? {
        return FirebaseAuth.getInstance().currentUser?.uid
    }

    override suspend fun getFreshAdminToken(): String? {
        val currentFirebaseUser = FirebaseAuth.getInstance().currentUser ?: return null
        val tokenResult = Tasks.await(currentFirebaseUser.getIdToken(true))
        return tokenResult?.token
    }

    override suspend fun updatePassword(newPassword: String) {
        val currentUser = FirebaseAuth.getInstance().currentUser ?: throw IllegalStateException("No authenticated Firebase user found")
        Tasks.await(currentUser.updatePassword(newPassword.trim()))
    }

    override fun signOut() {
        FirebaseAuth.getInstance().signOut()
    }
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
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val firebaseAuthProvider: FirebaseAuthProvider = DefaultFirebaseAuthProvider()
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
        val currentFirebaseUid = firebaseAuthProvider.getCurrentUid()

        var user = userDao.getUserById(session.userId)

        // If user is missing from Room, synchronize from remote authoritative profile
        if (user == null && currentFirebaseUid != null) {
            val authUserResult = fetchAuthoritativeProfileByAuthId(currentFirebaseUid)
            val authUser = authUserResult.getOrNull()
            if (authUser != null) {
                user = authUser
                sessionManager.saveSession(
                    accessToken = session.accessToken,
                    refreshToken = session.refreshToken,
                    userId = authUser.id,
                    registerNumber = authUser.registerNumber,
                    expiresInSeconds = ((session.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(60)
                )
            }
        }

        if (user == null || user.accountStatus != AccountStatus.ACTIVE.name || user.isSuspended) {
            sessionManager.clearSession()
            _currentProcessUserId.value = null
            SupabaseClient.currentAccessToken.set(null)
            return@withContext false
        }

        if (session.expiresAt > System.currentTimeMillis()) {
            _currentProcessUserId.value = user.id
            SupabaseClient.currentAccessToken.set(session.accessToken)
            Log.i(TAG, "Restored active session for ${user.registerNumber} (UUID: ${user.id})")
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
     * Refreshes the Firebase JWT token when nearing expiration or expired.
     */
    suspend fun refreshSessionIfNeeded(): Boolean = withContext(dispatcher) {
        val session = sessionManager.getSession() ?: return@withContext false
        if (session.expiresAt > System.currentTimeMillis() + 60_000) {
            return@withContext true
        }
        
        try {
            val userUid = firebaseAuthProvider.getCurrentUid()
            if (userUid == null) {
                logout()
                return@withContext false
            }
            
            // Force-refresh the signed-in user's Firebase ID token. If Firebase cannot issue
            // one, fail loudly instead of storing a fake token (the catch block below logs the
            // user out once the current token has actually expired).
            val newToken = firebaseAuthProvider.getFreshAdminToken()
                ?: throw IllegalStateException("Firebase did not return a fresh ID token")
            
            val expiresInSeconds = 3600L
            sessionManager.updateTokens(newToken, session.refreshToken, expiresInSeconds)
            SupabaseClient.currentAccessToken.set(newToken)
            _currentProcessUserId.value = session.userId
            Log.i(TAG, "Firebase JWT session refreshed successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Firebase JWT session refresh failed: ${e.message}")
            if (session.expiresAt <= System.currentTimeMillis()) {
                logout()
                false
            } else {
                true
            }
        }
    }

    /**
     * Resolves user profile generically from remote Supabase authoritative source using Firebase UID.
     * Maps to real PostgreSQL UUID and synchronizes Room database.
     */
    suspend fun fetchAuthoritativeProfileByAuthId(authUserId: String): Result<UserEntity> = withContext(dispatcher) {
        if (authUserId.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Auth user ID cannot be empty"))
        }
        try {
            val remoteUsers = SupabaseClient.restApi.getUserByAuthId("eq.$authUserId")
            val remoteDto = remoteUsers.firstOrNull()
            if (remoteDto == null || remoteDto.id.isNullOrBlank()) {
                return@withContext Result.failure(IllegalStateException("Profile not found. Please contact an administrator."))
            }

            val realId = remoteDto.id
            val isAdmin = remoteDto.isAdmin ?: false
            val regNumber = remoteDto.registerNumber?.ifBlank { if (isAdmin) "ADMIN" else "" } ?: (if (isAdmin) "ADMIN" else "")
            val defaultName = if (isAdmin) "Administrator" else "Student $regNumber"

            val entity = UserEntity(
                id = realId,
                authUserId = remoteDto.authUserId ?: authUserId,
                registerNumber = regNumber,
                name = remoteDto.name?.ifBlank { defaultName } ?: defaultName,
                phoneNumber = remoteDto.phoneNumber ?: "",
                department = remoteDto.department?.ifBlank { if (isAdmin) "Administration" else "Computer Science & Engineering" }
                    ?: (if (isAdmin) "Administration" else "Computer Science & Engineering"),
                email = remoteDto.email ?: "",
                emailVerified = remoteDto.emailVerified ?: true,
                accountStatus = remoteDto.accountStatus ?: AccountStatus.ACTIVE.name,
                isFirstLogin = remoteDto.isFirstLogin ?: false,
                isSuspended = remoteDto.isSuspended ?: false,
                isAdmin = isAdmin,
                createdAt = remoteDto.createdAt ?: System.currentTimeMillis(),
                approvedAt = remoteDto.approvedAt,
                approvedBy = remoteDto.approvedBy
            )

            // Clear any stale local user records that conflict with this authoritative profile
            if (isAdmin) {
                userDao.clearStaleAdminUsers(realId)
            } else if (regNumber.isNotBlank()) {
                val oldUser = userDao.getUserByRegisterNumber(regNumber)
                if (oldUser != null && oldUser.id != realId) {
                    userDao.deleteUserById(oldUser.id)
                }
            }

            userDao.insertUser(entity)
            Result.success(entity)
        } catch (e: Exception) {
            Log.w(TAG, "fetchAuthoritativeProfileByAuthId error for $authUserId: ${e.message}")
            val cached = userDao.getUserByAuthUserId(authUserId)
            if (cached != null) {
                return@withContext Result.success(cached)
            }
            Result.failure(IllegalStateException("Profile not found. Please contact an administrator."))
        }
    }

    /**
     * Updates an existing user profile row in public.users using their real PostgreSQL UUID.
     * Does NOT create synthetic IDs and does NOT insert new rows.
     */
    suspend fun updateProfile(
        userId: String,
        name: String? = null,
        phoneNumber: String? = null,
        department: String? = null
    ): Result<User> = withContext(dispatcher) {
        val firebaseUid = firebaseAuthProvider.getCurrentUid()

        // Ensure fresh Firebase ID token is active for PostgREST authorization
        if (firebaseUid != null) {
            try {
                val realToken = firebaseAuthProvider.getFreshAdminToken() ?: throw IllegalStateException("Failed to acquire token")
                SupabaseClient.currentAccessToken.set(realToken)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to refresh Firebase token before profile update: ${e.message}")
            }
        }

        // Resolve target user from Room or Supabase by auth_user_id
        var localUser: UserEntity? = null
        if (firebaseUid != null) {
            localUser = userDao.getUserByAuthUserId(firebaseUid)
        }
        if (localUser == null) {
            localUser = userDao.getUserById(userId)
        }
        if (localUser == null && firebaseUid != null) {
            val fetched = fetchAuthoritativeProfileByAuthId(firebaseUid)
            localUser = fetched.getOrNull()
        }

        if (localUser == null) {
            return@withContext Result.failure(IllegalStateException("Profile not found. Please contact an administrator."))
        }

        val realUserId = localUser.id


        val targetId = localUser.id
        val updates = mutableMapOf<String, Any?>()
        if (name != null) updates["name"] = name.trim()
        if (phoneNumber != null) updates["phone_number"] = phoneNumber.trim()
        if (department != null) updates["department"] = department.trim()

        if (updates.isEmpty()) {
            return@withContext Result.success(localUser.toDomain())
        }

        // Call Supabase PostgREST PATCH users?id=eq.<targetId>
        try {
            val resp = SupabaseClient.restApi.updateUser("eq.$targetId", updates)
            if (!resp.isSuccessful) {
                val errorBody = resp.errorBody()?.string()
                Log.e(TAG, "Supabase profile update failed: ${resp.code()} - $errorBody")
                val safeErr = extractSafeErrorMessage(errorBody, "Profile update failed (${resp.code()})")
                return@withContext Result.failure(IllegalStateException(safeErr))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Supabase profile update network error: ${e.message}", e)
            return@withContext Result.failure(IllegalStateException("Network error updating profile: ${e.message}"))
        }

        // Synchronize local Room entity
        val updatedEntity = localUser.copy(
            name = (updates["name"] as? String) ?: localUser.name,
            phoneNumber = (updates["phone_number"] as? String) ?: localUser.phoneNumber,
            department = (updates["department"] as? String) ?: localUser.department
        )
        userDao.updateUser(updatedEntity)
        Log.i(TAG, "Profile updated successfully for PostgreSQL UUID: $targetId (auth_user_id: ${localUser.authUserId})")
        Result.success(updatedEntity.toDomain())
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
                        userId = b.userId ?: (localUser?.id ?: ""), 
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
                        userId = b.userId ?: (localUser?.id ?: ""), 
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
            val firebaseUid = firebaseEmailPasswordSignIn(identity.authEmail, password.trim())
            val firebaseIdToken = firebaseAuthProvider.getFreshAdminToken() ?: throw IllegalStateException("Failed to acquire token")

            accessToken = firebaseIdToken
            refreshToken = ""
            expiresIn = 3600L
            supabaseUserId = firebaseUid
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

        // 3. Ensure local user profile exists by fetching authoritative profile from Supabase
        var localUser: UserEntity? = null
        if (supabaseUserId != null) {
            val authUserResult = fetchAuthoritativeProfileByAuthId(supabaseUserId)
            localUser = authUserResult.getOrNull()
        }

        if (localUser == null) {
            val existing = userDao.getUserByRegisterNumber(normalizedReg)
            if (existing != null) {
                localUser = existing
            } else {
                try {
                    val regResp = SupabaseClient.restApi.getUserByRegisterNumber("eq.$normalizedReg")
                    val remoteDto = regResp.firstOrNull()
                    if (remoteDto != null && !remoteDto.id.isNullOrBlank()) {
                        val realId = remoteDto.id
                        val isAdminUser = remoteDto.isAdmin ?: false
                        val regNumber = remoteDto.registerNumber?.ifBlank { if (isAdminUser) "ADMIN" else "" } ?: (if (isAdminUser) "ADMIN" else "")
                        val defaultName = if (isAdminUser) "Administrator" else "Student $regNumber"

                        localUser = UserEntity(
                            id = realId,
                            authUserId = remoteDto.authUserId,
                            registerNumber = regNumber,
                            name = remoteDto.name?.ifBlank { defaultName } ?: defaultName,
                            phoneNumber = remoteDto.phoneNumber ?: "",
                            department = remoteDto.department?.ifBlank { if (isAdminUser) "Administration" else "Computer Science & Engineering" }
                                ?: (if (isAdminUser) "Administration" else "Computer Science & Engineering"),
                            email = remoteDto.email ?: "",
                            emailVerified = remoteDto.emailVerified ?: true,
                            accountStatus = remoteDto.accountStatus ?: AccountStatus.ACTIVE.name,
                            isFirstLogin = remoteDto.isFirstLogin ?: false,
                            isSuspended = remoteDto.isSuspended ?: false,
                            isAdmin = isAdminUser,
                            createdAt = remoteDto.createdAt ?: System.currentTimeMillis(),
                            approvedAt = remoteDto.approvedAt,
                            approvedBy = remoteDto.approvedBy
                        )
                        userDao.insertUser(localUser)
                    } else {
                        return@withContext Result.failure(IllegalStateException("Profile not found. Please contact an administrator."))
                    }
                } catch (e: Exception) {
                    return@withContext Result.failure(IllegalStateException("Profile not found and network unavailable. Please contact an administrator."))
                }
            }
        }

        val finalUser = localUser ?: return@withContext Result.failure(IllegalStateException("Profile could not be resolved."))

        // 4. Daily OTP Verification Check
        if (identity.alreadyVerifiedToday) {
            _currentProcessUserId.value = finalUser.id
            SupabaseClient.currentAccessToken.set(accessToken)
            sessionManager.saveSession(
                accessToken = accessToken,
                refreshToken = refreshToken,
                userId = finalUser.id,
                registerNumber = finalUser.registerNumber,
                expiresInSeconds = expiresIn
            )
            recordLoginEvent(finalUser, "SUCCESS", accessToken)
            return@withContext Result.success(LoginStep.Authenticated(finalUser.toDomain()))
        }

        // Request Daily Login OTP from Supabase Edge Function
        try {
            val otpResponse = authApi.sendLoginOtp("Bearer $accessToken")
            if (otpResponse.isSuccessful && otpResponse.body() != null) {
                val otpBody = otpResponse.body()!!
                if (otpBody.alreadyVerifiedToday) {
                    _currentProcessUserId.value = finalUser.id
                    SupabaseClient.currentAccessToken.set(accessToken)
                    sessionManager.saveSession(
                        accessToken = accessToken,
                        refreshToken = refreshToken,
                        userId = finalUser.id,
                        registerNumber = finalUser.registerNumber,
                        expiresInSeconds = expiresIn
                    )
                    recordLoginEvent(finalUser, "SUCCESS", accessToken)
                    return@withContext Result.success(LoginStep.Authenticated(finalUser.toDomain()))
                } else {
                    // Daily OTP required; do NOT save persistent session yet
                    return@withContext Result.success(
                        LoginStep.OtpRequired(
                            EmailOtpChallenge(
                                userId = finalUser.id,
                                registerNumber = finalUser.registerNumber,
                                maskedEmail = otpBody.emailMasked ?: identity.maskedEmail,
                                expiresInSeconds = otpBody.expiresInSeconds,
                                accessToken = accessToken,
                                refreshToken = refreshToken
                            )
                        )
                    )
                }
            } else {
                val errorBodyString = otpResponse.errorBody()?.string()
                var errorMessage = "Failed to request email OTP: ${otpResponse.code()}"
                try {
                    if (errorBodyString != null) {
                        val json = org.json.JSONObject(errorBodyString)
                        if (json.has("error")) {
                            errorMessage = json.getString("error")
                        }
                    }
                } catch (ignored: Exception) {}

                if (com.example.BuildConfig.DEBUG) {
                    Log.d("LIFE_AUTH_DIAGNOSTIC", "OTP_REQUEST_FAILED: ${otpResponse.code()} - $errorMessage")
                }
                return@withContext Result.failure(IllegalStateException(errorMessage))
            }
        } catch (e: Exception) {
            if (com.example.BuildConfig.DEBUG) {
                Log.d("LIFE_AUTH_DIAGNOSTIC", "OTP_REQUEST_FAILED: ${e.javaClass.simpleName}")
            }
            return@withContext Result.failure(IllegalStateException("Network error requesting email OTP"))
        }
    }

    private suspend fun firebaseEmailPasswordSignIn(email: String, password: String): String {
        return firebaseAuthProvider.signInWithEmailAndPassword(email, password)
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

        try {
            val firebaseUid = firebaseEmailPasswordSignIn(trimmedEmail, password.trim())
            val idToken = firebaseAuthProvider.getFreshAdminToken() ?: throw IllegalStateException("Failed to acquire token")

            SupabaseClient.currentAccessToken.set(idToken)

            val profileResult = fetchAuthoritativeProfileByAuthId(firebaseUid)
            val matchingUser = profileResult.getOrElse { e ->
                SupabaseClient.currentAccessToken.set(null)
                return@withContext Result.failure(IllegalStateException("Failed to load admin profile: ${e.message}"))
            }

            if (!matchingUser.isAdmin) {
                SupabaseClient.currentAccessToken.set(null)
                return@withContext Result.failure(IllegalStateException("Admin access denied"))
            }

            if (!isAdminAccessAllowed(matchingUser)) {
                SupabaseClient.currentAccessToken.set(null)
                val reason = when (matchingUser.accountStatus) {
                    AccountStatus.PENDING.name -> "Admin account is pending approval."
                    AccountStatus.SUSPENDED.name -> "Admin account is suspended."
                    AccountStatus.REJECTED.name -> "Admin account is not authorized."
                    else -> "Admin access denied."
                }
                return@withContext Result.failure(IllegalStateException(reason))
            }

            // Session logging per Section 9 (without sensitive credentials/tokens)
            Log.i(TAG, "Admin login session established: Firebase UID=${firebaseUid}, public.users.id=${matchingUser.id}, register number=${matchingUser.registerNumber}, is_admin=${matchingUser.isAdmin}")

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
                val fbUid = firebaseAuthProvider.getCurrentUid() ?: challenge.userId
                if (!fbUid.isBlank()) {
                    fetchAuthoritativeProfileByAuthId(fbUid)
                }

                val userEntity = userDao.getUserByAuthUserId(fbUid)
                    ?: userDao.getUserByRegisterNumber(challenge.registerNumber)
                    ?: userDao.getUserById(challenge.userId)
                    ?: return@withContext Result.failure(IllegalStateException("Profile not found. Please contact an administrator."))

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
                val errorBodyString = verifyRes.errorBody()?.string()
                var errorMsg = verifyRes.body()?.error ?: "Invalid OTP"
                try {
                    if (!errorBodyString.isNullOrBlank()) {
                        val json = org.json.JSONObject(errorBodyString)
                        if (json.has("error")) {
                            errorMsg = json.getString("error")
                        }
                    }
                } catch (ignored: Exception) {}
                return@withContext Result.failure(IllegalArgumentException(errorMsg))
            }
        } catch (e: Exception) {
            return@withContext Result.failure(IllegalStateException(e.message ?: "Network error during OTP verification"))
        }
    }

    suspend fun resendEmailOtp(challenge: EmailOtpChallenge): Result<EmailOtpChallenge> = withContext(dispatcher) {
        try {
            val token = firebaseAuthProvider.getFreshAdminToken() ?: challenge.accessToken
            val otpResponse = authApi.sendLoginOtp("Bearer $token")
            if (otpResponse.isSuccessful && otpResponse.body() != null) {
                val otpBody = otpResponse.body()!!
                val newChallenge = challenge.copy(
                    maskedEmail = otpBody.emailMasked ?: challenge.maskedEmail,
                    expiresInSeconds = otpBody.expiresInSeconds,
                    accessToken = token
                )
                return@withContext Result.success(newChallenge)
            } else {
                val errorBodyString = otpResponse.errorBody()?.string()
                var errorMessage = "Failed to resend verification code"
                try {
                    if (!errorBodyString.isNullOrBlank()) {
                        val json = org.json.JSONObject(errorBodyString)
                        if (json.has("error")) {
                            errorMessage = json.getString("error")
                        }
                    }
                } catch (ignored: Exception) {}
                return@withContext Result.failure(IllegalStateException(errorMessage))
            }
        } catch (e: Exception) {
            return@withContext Result.failure(IllegalStateException(e.message ?: "Network error requesting new OTP"))
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
            firebaseAuthProvider.updatePassword(newPassword)

            // Obtain a fresh ID token to call server-side RPC
            val idToken = firebaseAuthProvider.getFreshAdminToken()
                ?: return@withContext Result.failure(
                    IllegalStateException("Could not obtain a Firebase ID token to finalize first login")
                )

            // Call server-side function to mark first-login complete
            try {
                val resp = authApi.completeFirstLogin("Bearer $idToken")
                if (resp.isSuccessful && resp.body()?.ok == true) {
                    Log.i(TAG, "First-login completed server-side for Firebase UID: $userId")
                    userDao.updateFirstLoginStatus(userId, false)
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

    /**
     * Obtains a fresh Firebase ID token for the currently signed in Admin user without changing session state.
     */
    private suspend fun getFreshAdminToken(): Result<String> {
        val token = firebaseAuthProvider.getFreshAdminToken()
        return if (token != null) {
            SupabaseClient.currentAccessToken.set(token)
            Result.success(token)
        } else {
            Result.failure(IllegalStateException("No active admin session. Please log in as admin."))
        }
    }

    // --- Admin Student Management Operations ---

    /**
     * Admin creates a student account server-side using admin-manage-user Edge Function.
     * The admin's client-side Firebase session remains unchanged.
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
            return@withContext Result.failure(IllegalArgumentException("An account with this register number already exists."))
        }

        val studentEmail = email.trim()
        if (studentEmail.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("A real email address is required for student account creation."))
        }
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(studentEmail).matches()) {
            return@withContext Result.failure(IllegalArgumentException("Please enter a valid email address."))
        }

        val adminTokenResult = getFreshAdminToken()
        val adminToken = adminTokenResult.getOrElse { err ->
            return@withContext Result.failure(err)
        }

        val beforeAdminUid = firebaseAuthProvider.getCurrentUid()
        Log.i(TAG, "Admin Firebase UID before Create Student: $beforeAdminUid")

        val resp = try {
            authApi.adminManageUser(
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
        } catch (e: Exception) {
            return@withContext Result.failure(IllegalStateException("Network error requesting student creation: ${e.message}"))
        }

        if (!resp.isSuccessful || resp.body()?.ok != true) {
            val errorBodyString = resp.errorBody()?.string()
            var errorMessage = resp.body()?.error ?: "Failed to create student (HTTP ${resp.code()})"
            try {
                if (!errorBodyString.isNullOrBlank()) {
                    val json = org.json.JSONObject(errorBodyString)
                    if (json.has("error")) {
                        errorMessage = json.getString("error")
                    }
                }
            } catch (ignored: Exception) {}
            return@withContext Result.failure(IllegalStateException(errorMessage))
        }

        val authUserId = resp.body()?.authUserId ?: resp.body()?.userId
        val afterAdminUid = firebaseAuthProvider.getCurrentUid()
        Log.i(TAG, "Admin Firebase UID after Create Student: $afterAdminUid (Session Intact: ${beforeAdminUid == afterAdminUid})")

        val userId = resp.body()?.userId ?: (authUserId ?: java.util.UUID.randomUUID().toString())
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
        Log.i(TAG, "Admin ${admin.registerNumber} created PENDING student account for $normalizedReg server-side")
        Result.success(entity.toDomain())
    }

    suspend fun deleteStudentAccount(
        adminUserId: String,
        targetUserId: String
    ): Result<Unit> = withContext(dispatcher) {
        val admin = userDao.getUserById(adminUserId)
        if (admin == null || !admin.isAdmin) {
            return@withContext Result.failure(IllegalStateException("Unauthorized: Admin privileges required"))
        }
        
        val adminTokenResult = getFreshAdminToken()
        val adminToken = adminTokenResult.getOrElse { err ->
            return@withContext Result.failure(err)
        }

        try {
            val resp = authApi.adminManageUser(
                "Bearer $adminToken",
                mapOf(
                    "action" to "delete_student",
                    "target_user_id" to targetUserId
                )
            )
            
            if (resp.isSuccessful && resp.body()?.ok == true) {
                userDao.deleteUserById(targetUserId)
                return@withContext Result.success(Unit)
            } else {
                val errorBodyString = resp.errorBody()?.string()
                var errorMessage = "Failed to delete student: ${resp.code()}"
                try {
                    if (errorBodyString != null) {
                        val json = org.json.JSONObject(errorBodyString)
                        if (json.has("error")) {
                            errorMessage = json.getString("error")
                        }
                    }
                } catch (ignored: Exception) {}
                
                return@withContext Result.failure(IllegalStateException(errorMessage))
            }
        } catch (e: Exception) {
            return@withContext Result.failure(IllegalStateException("Network error requesting student deletion"))
        }
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
                val authUserId = resp.body()?.authUserId ?: resp.body()?.userId
                val userId = resp.body()?.userId ?: (authUserId ?: java.util.UUID.randomUUID().toString())
                val entity = UserEntity(
                    id = userId,
                    authUserId = authUserId,
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
                val safeErr = extractSafeErrorMessage(resp.errorBody()?.string(), resp.body()?.error ?: "Unable to create account. Please try again.")
                return@withContext Result.failure(IllegalStateException(safeErr))
            }
        } catch (e: Exception) {
            return@withContext Result.failure(IllegalStateException("Unable to create account. Please try again."))
        }
    }

    private fun extractSafeErrorMessage(errorBody: String?, fallback: String): String {
        if (errorBody.isNullOrBlank()) return fallback
        var rawMsg = fallback
        try {
            val json = org.json.JSONObject(errorBody)
            if (json.has("error")) {
                val err = json.getString("error")
                if (err.isNotBlank()) rawMsg = err
            } else if (json.has("message")) {
                val msg = json.getString("message")
                if (msg.isNotBlank()) rawMsg = msg
            }
        } catch (ignored: Exception) {
            rawMsg = if (errorBody.length > 150) fallback else errorBody
        }
        return rawMsg
    }

    suspend fun approveStudentAccount(adminUserId: String, targetUserId: String): Result<Unit> = withContext(dispatcher) {
        val admin = userDao.getUserById(adminUserId)
        if (admin == null || !admin.isAdmin) {
            return@withContext Result.failure(IllegalStateException("Unauthorized: Admin privileges required"))
        }
        val target = userDao.getUserById(targetUserId) ?: return@withContext Result.failure(IllegalArgumentException("Student not found"))
        val adminTokenResult = getFreshAdminToken()
        val adminToken = adminTokenResult.getOrElse { err ->
            return@withContext Result.failure(err)
        }
        try {
            val resp = authApi.adminManageUser(
                "Bearer $adminToken",
                mapOf(
                    "action" to "set_status",
                    "target_user_id" to targetUserId,
                    "status" to AccountStatus.ACTIVE.name
                )
            )
            if (!resp.isSuccessful || resp.body()?.ok != true) {
                val err = extractSafeErrorMessage(resp.errorBody()?.string(), "Unable to approve student (${resp.code()})")
                return@withContext Result.failure(IllegalStateException(err))
            }
        } catch (e: Exception) {
            Log.e(TAG, "approveStudentAccount error: ${e.message}", e)
            return@withContext Result.failure(IllegalStateException(e.message ?: "Unable to approve student"))
        }
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
        val adminTokenResult = getFreshAdminToken()
        val adminToken = adminTokenResult.getOrElse { err ->
            return@withContext Result.failure(err)
        }
        try {
            val resp = authApi.adminManageUser(
                "Bearer $adminToken",
                mapOf(
                    "action" to "set_status",
                    "target_user_id" to targetUserId,
                    "status" to AccountStatus.REJECTED.name
                )
            )
            if (!resp.isSuccessful || resp.body()?.ok != true) {
                val err = extractSafeErrorMessage(resp.errorBody()?.string(), "Unable to reject student (${resp.code()})")
                return@withContext Result.failure(IllegalStateException(err))
            }
        } catch (e: Exception) {
            Log.e(TAG, "rejectStudentAccount error: ${e.message}", e)
            return@withContext Result.failure(IllegalStateException(e.message ?: "Unable to reject student"))
        }
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
        val adminTokenResult = getFreshAdminToken()
        val adminToken = adminTokenResult.getOrElse { err ->
            return@withContext Result.failure(err)
        }
        try {
            val resp = authApi.adminManageUser(
                "Bearer $adminToken",
                mapOf(
                    "action" to "set_status",
                    "target_user_id" to targetUserId,
                    "status" to AccountStatus.SUSPENDED.name
                )
            )
            if (!resp.isSuccessful || resp.body()?.ok != true) {
                val err = extractSafeErrorMessage(resp.errorBody()?.string(), "Unable to suspend student (${resp.code()})")
                return@withContext Result.failure(IllegalStateException(err))
            }
        } catch (e: Exception) {
            Log.e(TAG, "suspendStudentAccount error: ${e.message}", e)
            return@withContext Result.failure(IllegalStateException(e.message ?: "Unable to suspend student"))
        }
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
        val adminTokenResult = getFreshAdminToken()
        val adminToken = adminTokenResult.getOrElse { err ->
            return@withContext Result.failure(err)
        }
        try {
            val resp = authApi.adminManageUser(
                "Bearer $adminToken",
                mapOf(
                    "action" to "set_status",
                    "target_user_id" to targetUserId,
                    "status" to AccountStatus.ACTIVE.name
                )
            )
            if (!resp.isSuccessful || resp.body()?.ok != true) {
                val err = extractSafeErrorMessage(resp.errorBody()?.string(), "Unable to restore student (${resp.code()})")
                return@withContext Result.failure(IllegalStateException(err))
            }
        } catch (e: Exception) {
            Log.e(TAG, "restoreStudentAccount error: ${e.message}", e)
            return@withContext Result.failure(IllegalStateException(e.message ?: "Unable to restore student"))
        }
        userDao.updateAccountStatus(
            id = targetUserId,
            status = AccountStatus.ACTIVE.name,
            isSuspended = false
        )
        Log.i(TAG, "Admin ${admin.registerNumber} RESTORED account for ${target.registerNumber}")
        Result.success(Unit)
    }
}
