import re

with open('./app/src/main/java/com/example/data/repository/AuthRepository.kt', 'r') as f:
    content = f.read()

# Update OtpChallenge
content = re.sub(
    r'data class OtpChallenge\([\s\S]*?timestamp: Long = System.currentTimeMillis\(\)\n\)',
    '''data class OtpChallenge(
    val userId: String,
    val registerNumber: String,
    val maskedPhoneNumber: String,
    val fullPhoneNumber: String,
    val email: String,
    val factorId: String,
    val challengeId: String,
    val timestamp: Long = System.currentTimeMillis()
)''',
    content
)

# Split content to replace the functions
part1 = content.split('suspend fun requestLoginOtp')[0]

# find where verifyOtp ends, or just login starts
part2 = '    /**\n     * Backward-compatible direct login for automated unit test flows.' + content.split('    /**\n     * Backward-compatible direct login for automated unit test flows.')[1]

new_functions = """    suspend fun requestLoginOtp(registerNumber: String, password: String): Result<OtpChallenge> = withContext(dispatcher) {
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
"""

with open('./app/src/main/java/com/example/data/repository/AuthRepository.kt', 'w') as f:
    f.write(part1 + new_functions + '\n' + part2)

print("Updated AuthRepository.kt")
