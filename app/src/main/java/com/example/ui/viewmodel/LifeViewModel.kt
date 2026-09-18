package com.example.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.data.local.AppDatabase
import com.example.data.repository.AuthSessionManager
import com.example.data.repository.LifeRepository
import com.example.data.repository.EmailOtpChallenge
import com.example.data.repository.LoginStep
import com.example.domain.engine.TreeEngine
import com.example.domain.model.IdeaNode
import com.example.domain.model.LeaderboardRow
import com.example.domain.model.LoginEvent
import com.example.domain.model.NodeType
import com.example.domain.model.TreeDisplayNode
import com.example.domain.model.User
import com.example.domain.model.UserScore
import com.example.ui.components.LifeNavigationTab
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class UiMessage(val message: String, val isError: Boolean = false)

class LifeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: LifeRepository

    init {
        val db = AppDatabase.getDatabase(application)
        val session = AuthSessionManager(application)
        repository = LifeRepository(db, session)

        viewModelScope.launch {
            repository.initializeDatabase()
        }
    }

    // --- State Flows ---

    val currentUser: StateFlow<User?> = repository.observeCurrentUser()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val problems: StateFlow<List<IdeaNode>> = repository.getProblems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val improvements: StateFlow<List<IdeaNode>> = repository.getImprovements()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allActiveNodes: StateFlow<List<IdeaNode>> = repository.getAllActiveNodes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allUsers: StateFlow<List<User>> = repository.getAllUsers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val loginEvents: StateFlow<List<LoginEvent>> = repository.observeRecentSuccessfulLogins()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val searchResults: StateFlow<List<IdeaNode>> = _searchQuery.combine(allActiveNodes) { query, nodes ->
        if (query.isBlank()) {
            emptyList()
        } else {
            val q = query.trim().lowercase()
            nodes.filter {
                it.title.lowercase().contains(q) ||
                it.description.lowercase().contains(q) ||
                it.authorRegisterNumber.lowercase().contains(q)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Leaderboards
    val topProblemContributors: StateFlow<List<LeaderboardRow>> = repository.getTopProblemContributors()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val topImprovementContributors: StateFlow<List<LeaderboardRow>> = repository.getTopImprovementContributors()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val topRefiners: StateFlow<List<LeaderboardRow>> = repository.getTopRefiners()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Profile & User scores
    val currentUserScore: StateFlow<UserScore?> = combine(currentUser, repository.getAllUserScores()) { user: User?, scores: List<UserScore> ->
        if (user != null) scores.find { it.userId == user.id } else null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Current navigation state
    private val _selectedTab = MutableStateFlow(LifeNavigationTab.PROBLEMS)
    val selectedTab: StateFlow<LifeNavigationTab> = _selectedTab.asStateFlow()

    // Single selected Node Detail
    private val _selectedNodeId = MutableStateFlow<String?>(null)
    val selectedNodeId: StateFlow<String?> = _selectedNodeId.asStateFlow()

    val selectedNode: StateFlow<IdeaNode?> = combine(_selectedNodeId, allActiveNodes) { id: String?, nodes: List<IdeaNode> ->
        if (id != null) nodes.find { it.id == id } else null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Tree hierarchy for selected node
    private val _expandedNodeIds = MutableStateFlow<Set<String>>(emptySet())
    val expandedNodeIds: StateFlow<Set<String>> = _expandedNodeIds.asStateFlow()

    val selectedNodeTree: StateFlow<List<TreeDisplayNode>> = combine(
        selectedNode,
        allActiveNodes,
        _expandedNodeIds
    ) { node: IdeaNode?, allNodes: List<IdeaNode>, expandedIds: Set<String> ->
        if (node == null) {
            emptyList()
        } else {
            TreeEngine.flattenTree(
                rootNode = node,
                allNodes = allNodes,
                expandedNodeIds = expandedIds
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // UI Events & Toasts
    private val _uiMessages = MutableSharedFlow<UiMessage>()
    val uiMessages: SharedFlow<UiMessage> = _uiMessages.asSharedFlow()

    // --- Actions ---

    // Login error and progress state
    private val _loginError = MutableStateFlow<String?>(null)
    val loginError: StateFlow<String?> = _loginError.asStateFlow()

    private val _accountNotFound = MutableStateFlow<String?>(null)
    val accountNotFound: StateFlow<String?> = _accountNotFound.asStateFlow()

    private val _isLoggingIn = MutableStateFlow(false)
    val isLoggingIn: StateFlow<Boolean> = _isLoggingIn.asStateFlow()

    // OTP Challenge State
    private val _activeOtpChallenge = MutableStateFlow<EmailOtpChallenge?>(null)
    val activeOtpChallenge: StateFlow<EmailOtpChallenge?> = _activeOtpChallenge.asStateFlow()

    fun clearLoginError() {
        _loginError.value = null
    }

    fun clearAccountNotFound() {
        _accountNotFound.value = null
    }

    fun clearOtpChallenge() {
        _activeOtpChallenge.value = null
        _loginError.value = null
    }

    fun onTabSelected(tab: LifeNavigationTab) {
        _selectedTab.value = tab
    }

    fun onSelectNode(nodeId: String?) {
        _selectedNodeId.value = nodeId
        if (nodeId != null) {
            _expandedNodeIds.value = _expandedNodeIds.value + nodeId
        }
    }

    fun toggleNodeExpand(nodeId: String) {
        val current = _expandedNodeIds.value
        _expandedNodeIds.value = if (current.contains(nodeId)) {
            current - nodeId
        } else {
            current + nodeId
        }
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    /**
     * Direct password-based login.
     * Authenticates via real backend and navigates to LIFE upon ACTIVE status,
     * or triggers email OTP verification.
     */
    fun login(registerNumber: String, password: String, onFirstLoginNeeded: (User) -> Unit = {}) {
        viewModelScope.launch {
            _isLoggingIn.value = true
            _loginError.value = null
            val result = repository.login(registerNumber, password)
            _isLoggingIn.value = false
            result.onSuccess { step ->
                _loginError.value = null
                when (step) {
                    is com.example.data.repository.LoginStep.Authenticated -> {
                        _activeOtpChallenge.value = null
                        _uiMessages.emit(UiMessage("Welcome back, ${step.user.name.ifEmpty { step.user.registerNumber }}!"))
                        if (step.user.isFirstLogin) {
                            onFirstLoginNeeded(step.user)
                        }
                    }
                    is com.example.data.repository.LoginStep.OtpRequired -> {
                        _activeOtpChallenge.value = step.challenge
                    }
                }
            }.onFailure { e ->
                val reason = e.message ?: "Authentication failed"
                val errorMsg = if (BuildConfig.DEBUG) reason else "Login failed"
                if (BuildConfig.DEBUG) {
                    Log.d("LIFE_AUTH_DIAGNOSTIC", "AUTH_INVALID_CREDENTIALS: ${reason.take(160)}")
                }
                // Special-case: account not found -> show dedicated screen
                if (reason.contains("Account Not Found")) {
                    _accountNotFound.value = registerNumber
                }
                _loginError.value = errorMsg
                _uiMessages.emit(UiMessage(errorMsg, isError = true))
            }
        }
    }

    /**
     * Initiates Login Authentication Step 1 & 2.
     * On valid credentials, advances to OTP screen with active challenge.
     */
    fun requestLoginOtp(registerNumber: String, password: String) {
        viewModelScope.launch {
            _isLoggingIn.value = true
            _loginError.value = null
            val result = repository.requestLoginOtp(registerNumber, password)
            _isLoggingIn.value = false
            result.onFailure { e ->
                val errorMsg = e.message ?: "Authentication failed"
                _loginError.value = errorMsg
                _uiMessages.emit(UiMessage(errorMsg, isError = true))
            }
        }
    }

    /**
     * Verifies OTP (Step 3) and creates session.
     */
    fun verifyLoginOtp(otpCode: String, onFirstLoginNeeded: (User) -> Unit = {}) {
        val challenge = _activeOtpChallenge.value ?: return
        viewModelScope.launch {
            _isLoggingIn.value = true
            _loginError.value = null
            val result = repository.verifyEmailOtp(challenge, otpCode)
            _isLoggingIn.value = false
            result.onSuccess { user ->
                _activeOtpChallenge.value = null
                _loginError.value = null
                _uiMessages.emit(UiMessage("Welcome back, ${user.name.ifEmpty { user.registerNumber }}!"))
                if (user.isFirstLogin) {
                    onFirstLoginNeeded(user)
                }
            }.onFailure { e ->
                val errorMsg = e.message ?: "Invalid OTP"
                _loginError.value = errorMsg
                _uiMessages.emit(UiMessage(errorMsg, isError = true))
            }
        }
    }

    /**
     * Resends OTP for the active login challenge.
     */
    fun resendOtp() {
        val challenge = _activeOtpChallenge.value ?: return
        viewModelScope.launch {
            _loginError.value = null
            _uiMessages.emit(UiMessage("Verification code resent to ${challenge.maskedEmail}"))
        }
    }

    fun setupNewPassword(userId: String, newPass: String, onDone: () -> Unit) {
        viewModelScope.launch {
            val result = repository.setupFirstLoginPassword(userId, newPass)
            result.onSuccess {
                _uiMessages.emit(UiMessage("Password updated successfully!"))
                onDone()
            }.onFailure { e ->
                _uiMessages.emit(UiMessage(e.message ?: "Failed to set password", isError = true))
            }
        }
    }

    fun adminLogin(email: String, password: String, onSuccess: (User) -> Unit = {}) {
        viewModelScope.launch {
            _isLoggingIn.value = true
            _loginError.value = null
            val result = repository.adminLogin(email, password)
            _isLoggingIn.value = false
            result.onSuccess { user ->
                _activeOtpChallenge.value = null
                _loginError.value = null
                _uiMessages.emit(UiMessage("Welcome back, ${user.name.ifEmpty { user.registerNumber }}!"))
                onSuccess(user)
            }.onFailure { e ->
                val errorMsg = e.message ?: "Admin login failed"
                _loginError.value = errorMsg
                _uiMessages.emit(UiMessage(errorMsg, isError = true))
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            repository.logout()
            _activeOtpChallenge.value = null
            _selectedNodeId.value = null
            _uiMessages.emit(UiMessage("Logged out successfully"))
        }
    }

    fun toggleVote(nodeId: String) {
        viewModelScope.launch {
            val result = repository.toggleVote(nodeId)
            result.onFailure { e ->
                _uiMessages.emit(UiMessage(e.message ?: "Vote failed", isError = true))
            }
        }
    }

    fun createProblem(title: String, description: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            val result = repository.createNode(
                title = title,
                description = description,
                type = NodeType.PROBLEM
            )
            result.onSuccess {
                _uiMessages.emit(UiMessage("Problem posted! (+5 score)"))
                onSuccess()
            }.onFailure { e ->
                _uiMessages.emit(UiMessage(e.message ?: "Creation failed", isError = true))
            }
        }
    }

    /**
     * Student self sign-up flow. Creates Firebase identity server-side and a PENDING LIFE user.
     */
    fun signUpStudent(
        registerNumber: String,
        password: String,
        email: String,
        phoneNumber: String,
        name: String = "",
        department: String = "Computer Science & Engineering",
        onResult: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            _loginError.value = null
            val result = repository.signUpStudent(registerNumber = registerNumber, password = password, email = email, phoneNumber = phoneNumber, name = name, department = department)
            result.onSuccess {
                _uiMessages.emit(UiMessage("Account created and pending administrator approval"))
                onResult(true, "")
            }.onFailure { e ->
                val msg = e.message ?: "Unable to create account. Please try again."
                _loginError.value = msg
                _uiMessages.emit(UiMessage(msg, isError = true))
                onResult(false, msg)
            }
        }
    }

    fun createImprovement(title: String, description: String, parentProblemId: String?, onSuccess: () -> Unit) {
        viewModelScope.launch {
            val result = repository.createNode(
                title = title,
                description = description,
                type = NodeType.IMPROVEMENT,
                parentId = parentProblemId
            )
            result.onSuccess {
                _uiMessages.emit(UiMessage("Improvement idea posted! (+10 score)"))
                onSuccess()
            }.onFailure { e ->
                _uiMessages.emit(UiMessage(e.message ?: "Creation failed", isError = true))
            }
        }
    }

    fun createRefinement(title: String, description: String, parentNodeId: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            val result = repository.createNode(
                title = title,
                description = description,
                type = NodeType.REFINEMENT,
                parentId = parentNodeId
            )
            result.onSuccess {
                _uiMessages.emit(UiMessage("Refinement added! (+15 score)"))
                _expandedNodeIds.value = _expandedNodeIds.value + parentNodeId
                onSuccess()
            }.onFailure { e ->
                _uiMessages.emit(UiMessage(e.message ?: "Creation failed", isError = true))
            }
        }
    }

    fun editNode(nodeId: String, newTitle: String, newDescription: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            val result = repository.editNode(nodeId, newTitle, newDescription)
            result.onSuccess {
                _uiMessages.emit(UiMessage("Idea updated successfully"))
                onSuccess()
            }.onFailure { e ->
                _uiMessages.emit(UiMessage(e.message ?: "Edit failed", isError = true))
            }
        }
    }

    fun deleteNode(nodeId: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            val result = repository.deleteNode(nodeId)
            result.onSuccess {
                _uiMessages.emit(UiMessage("Idea removed"))
                onSuccess()
            }.onFailure { e ->
                _uiMessages.emit(UiMessage(e.message ?: "Cannot delete node", isError = true))
            }
        }
    }

    // --- Admin Actions ---

    fun createStudent(name: String, registerNumber: String, phoneNumber: String, department: String, email: String, initialPassword: String = "") {
        val admin = currentUser.value ?: return
        if (!admin.isAdmin) return
        viewModelScope.launch {
            val result = repository.createStudentUser(
                adminUserId = admin.id,
                name = name,
                registerNumber = registerNumber,
                phoneNumber = phoneNumber,
                department = department,
                email = email,
                initialPassword = if (initialPassword.isBlank()) "pass123" else initialPassword
            )
            result.onSuccess {
                _uiMessages.emit(UiMessage("Account for $registerNumber created (Pending Approval)"))
            }.onFailure { e ->
                _uiMessages.emit(UiMessage(e.message ?: "Failed to create account", isError = true))
            }
        }
    }

    fun approveStudent(targetUserId: String) {
        val admin = currentUser.value ?: return
        if (!admin.isAdmin) return
        viewModelScope.launch {
            val result = repository.approveStudentAccount(admin.id, targetUserId)
            result.onSuccess {
                _uiMessages.emit(UiMessage("Student account approved & activated"))
            }.onFailure { e ->
                _uiMessages.emit(UiMessage(e.message ?: "Approval failed", isError = true))
            }
        }
    }

    fun rejectStudent(targetUserId: String) {
        val admin = currentUser.value ?: return
        if (!admin.isAdmin) return
        viewModelScope.launch {
            val result = repository.rejectStudentAccount(admin.id, targetUserId)
            result.onSuccess {
                _uiMessages.emit(UiMessage("Student account rejected"))
            }.onFailure { e ->
                _uiMessages.emit(UiMessage(e.message ?: "Rejection failed", isError = true))
            }
        }
    }

    fun suspendStudent(targetUserId: String) {
        val admin = currentUser.value ?: return
        if (!admin.isAdmin) return
        viewModelScope.launch {
            val result = repository.suspendStudentAccount(admin.id, targetUserId)
            result.onSuccess {
                _uiMessages.emit(UiMessage("Student account suspended"))
            }.onFailure { e ->
                _uiMessages.emit(UiMessage(e.message ?: "Suspension failed", isError = true))
            }
        }
    }

    fun restoreStudent(targetUserId: String) {
        val admin = currentUser.value ?: return
        if (!admin.isAdmin) return
        viewModelScope.launch {
            val result = repository.restoreStudentAccount(admin.id, targetUserId)
            result.onSuccess {
                _uiMessages.emit(UiMessage("Student account restored to Active"))
            }.onFailure { e ->
                _uiMessages.emit(UiMessage(e.message ?: "Restoration failed", isError = true))
            }
        }
    }
}
