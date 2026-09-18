package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.repository.EmailOtpChallenge
import com.example.domain.model.IdeaNode
import com.example.domain.model.NodeType
import com.example.domain.model.User
import com.example.ui.components.LifeBottomNavigation
import com.example.ui.components.LifeNavigationTab
import com.example.ui.components.LifeTopBar
import com.example.ui.screens.admin.AdminManagementDialog
import com.example.ui.screens.auth.FirstPasswordDialog
import com.example.ui.screens.auth.LoginScreen
import com.example.ui.screens.auth.OtpScreen
import com.example.ui.screens.auth.SuspendedAccountScreen
import com.example.ui.screens.create.CreateNodeScreen
import com.example.ui.screens.detail.IdeaDetailScreen
import com.example.ui.screens.edit.EditNodeDialog
import com.example.ui.screens.improvements.ImprovementsFeedScreen
import com.example.ui.screens.leaderboard.LeaderboardScreen
import com.example.ui.screens.problems.ProblemsFeedScreen
import com.example.ui.screens.profile.ProfileScreen
import com.example.ui.screens.search.SearchScreen
import com.example.ui.viewmodel.LifeViewModel
import kotlinx.coroutines.flow.collectLatest

sealed class CreateMode {
    data object Problem : CreateMode()
    data class Improvement(val parentProblem: IdeaNode? = null) : CreateMode()
    data class Refinement(val parentIdea: IdeaNode) : CreateMode()
}

@Composable
fun LifeApp(
    viewModel: LifeViewModel = viewModel()
) {
    val currentUser by viewModel.currentUser.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val problems by viewModel.problems.collectAsState()
    val improvements by viewModel.improvements.collectAsState()
    val allActiveNodes by viewModel.allActiveNodes.collectAsState()
    val allUsers by viewModel.allUsers.collectAsState()
    val loginEvents by viewModel.loginEvents.collectAsState()

    val topProblems by viewModel.topProblemContributors.collectAsState()
    val topImprovements by viewModel.topImprovementContributors.collectAsState()
    val topRefiners by viewModel.topRefiners.collectAsState()

    val currentUserScore by viewModel.currentUserScore.collectAsState()

    val selectedNodeId by viewModel.selectedNodeId.collectAsState()
    val selectedNode by viewModel.selectedNode.collectAsState()
    val selectedNodeTree by viewModel.selectedNodeTree.collectAsState()

    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val loginError by viewModel.loginError.collectAsState()
    val isLoggingIn by viewModel.isLoggingIn.collectAsState()
    val activeOtpChallenge by viewModel.activeOtpChallenge.collectAsState()
    val accountNotFound by viewModel.accountNotFound.collectAsState()

    // Navigation sub-states
    var isSearching by remember { mutableStateOf(false) }
    var createMode by remember { mutableStateOf<CreateMode?>(null) }
    var editingNode by remember { mutableStateOf<IdeaNode?>(null) }
    var showAdminDialog by remember { mutableStateOf(false) }
    var showSignUp by remember { mutableStateOf(false) }
    var showSignUpSubmitted by remember { mutableStateOf(false) }
    var showAdminLogin by remember { mutableStateOf(false) }
    var firstLoginUser by remember { mutableStateOf<User?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.uiMessages.collectLatest { msg ->
            snackbarHostState.showSnackbar(msg.message)
        }
    }

    // Intercept back button for modal states
    BackHandler(enabled = activeOtpChallenge != null || isSearching || createMode != null || selectedNodeId != null) {
        when {
            activeOtpChallenge != null -> viewModel.clearOtpChallenge()
            createMode != null -> createMode = null
            isSearching -> isSearching = false
            selectedNodeId != null -> viewModel.onSelectNode(null)
        }
    }

    // 1. Unauthenticated View (Login / OTP)
    if (currentUser == null) {
        Box(modifier = Modifier.fillMaxSize()) {
            val challenge = activeOtpChallenge
            if (challenge != null) {
                OtpScreen(
                    maskedEmail = challenge.maskedEmail,
                    onVerifyOtp = { otpCode ->
                        viewModel.verifyLoginOtp(otpCode, onFirstLoginNeeded = { u ->
                            firstLoginUser = u
                        })
                    },
                    onResendOtp = { viewModel.resendOtp() },
                    onBack = { viewModel.clearOtpChallenge() },
                    serverError = loginError,
                    isLoading = isLoggingIn,
                    onClearError = { viewModel.clearLoginError() }
                )
            } else {
                if (accountNotFound != null) {
                    com.example.ui.screens.auth.AccountNotFoundScreen(
                        onCreateAccount = {
                            viewModel.clearAccountNotFound()
                            showSignUp = true
                        },
                        onBackToSignIn = { viewModel.clearAccountNotFound() }
                    )
                } else if (showSignUp) {
                    com.example.ui.screens.auth.SignUpScreen(
                        onCreateAccount = { reg, pass, email, phone, name, dept ->
                            showSignUp = false
                            viewModel.signUpStudent(reg, pass, email, phone, name ?: "", dept ?: "Computer Science & Engineering") { success, _ ->
                                if (success) showSignUpSubmitted = true
                            }
                        },
                        onBackToSignIn = { showSignUp = false },
                        serverError = loginError,
                        isLoading = isLoggingIn
                    )
                } else if (showSignUpSubmitted) {
                    com.example.ui.screens.auth.SignupSubmittedScreen(
                        onBackToSignIn = { showSignUpSubmitted = false }
                    )
                } else if (showAdminLogin) {
                    com.example.ui.screens.auth.AdminLoginScreen(
                        onAdminLoginSubmit = { email, pass ->
                            viewModel.adminLogin(email, pass) {
                                showAdminLogin = false
                            }
                        },
                        onBackToStudentLogin = {
                            showAdminLogin = false
                            viewModel.clearLoginError()
                        },
                        serverError = loginError,
                        isLoading = isLoggingIn,
                        onClearError = { viewModel.clearLoginError() }
                    )
                } else {
                    LoginScreen(
                        onLoginSubmit = { reg, pass ->
                            viewModel.login(reg, pass, onFirstLoginNeeded = { u ->
                                firstLoginUser = u
                            })
                        },
                        onSignUpClick = { showSignUp = true },
                        onAdminLoginClick = { showAdminLogin = true },
                        serverError = loginError,
                        isLoading = isLoggingIn,
                        onClearError = { viewModel.clearLoginError() }
                    )
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
            )
        }

        firstLoginUser?.let { user ->
            FirstPasswordDialog(
                onConfirmNewPassword = { newPass ->
                    viewModel.setupNewPassword(user.id, newPass) {
                        firstLoginUser = null
                    }
                },
                onDismiss = { firstLoginUser = null }
            )
        }
        return
    }

    // 2. Suspended Account Check
    val activeUser = currentUser
    if (activeUser != null && activeUser.isSuspended) {
        SuspendedAccountScreen(
            registerNumber = activeUser.registerNumber,
            onLogout = { viewModel.logout() }
        )
        return
    }

    // 3. Authenticated View
    LaunchedEffect(activeUser?.id) {
        if (activeUser != null && !activeUser.isSuspended) {
            android.util.Log.d("LIFE_REAL_LOGIN", "NAVIGATION")
            android.util.Log.d("LIFE_LOGIN_DEBUG", "NAVIGATING_HOME")
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("main_scaffold"),
        topBar = {
            val topBarTitle = when {
                createMode is CreateMode.Problem -> "Post Problem"
                createMode is CreateMode.Improvement -> "Share Improvement"
                createMode is CreateMode.Refinement -> "Refine Idea"
                isSearching -> "Search Evolution Tree"
                selectedNodeId != null -> "Idea Evolution"
                selectedTab == LifeNavigationTab.PROBLEMS -> "LIFE - Problems"
                selectedTab == LifeNavigationTab.IMPROVEMENTS -> "LIFE - Improvements"
                selectedTab == LifeNavigationTab.LEADERBOARD -> "Leaderboard"
                selectedTab == LifeNavigationTab.PROFILE -> "Student Profile"
                else -> "LIFE"
            }

            LifeTopBar(
                title = topBarTitle,
                onSearchClick = { isSearching = !isSearching },
                onBackClick = if (selectedNodeId != null || isSearching || createMode != null) {
                    {
                        if (createMode != null) createMode = null
                        else if (isSearching) isSearching = false
                        else viewModel.onSelectNode(null)
                    }
                } else null
            )
        },
        bottomBar = {
            if (createMode == null && selectedNodeId == null && !isSearching) {
                LifeBottomNavigation(
                    selectedTab = selectedTab,
                    onTabSelected = { tab ->
                        viewModel.onTabSelected(tab)
                    }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                // Creation Flows
                createMode is CreateMode.Problem -> {
                    CreateNodeScreen(
                        targetType = NodeType.PROBLEM,
                        onSubmitProblem = { title, desc ->
                            viewModel.createProblem(title, desc) {
                                createMode = null
                            }
                        },
                        onSubmitImprovement = { _, _, _ -> },
                        onSubmitRefinement = { _, _, _ -> },
                        onCancel = { createMode = null }
                    )
                }

                createMode is CreateMode.Improvement -> {
                    CreateNodeScreen(
                        targetType = NodeType.IMPROVEMENT,
                        parentNode = (createMode as CreateMode.Improvement).parentProblem,
                        availableProblems = problems,
                        onSubmitProblem = { _, _ -> },
                        onSubmitImprovement = { title, desc, parentProbId ->
                            viewModel.createImprovement(title, desc, parentProbId) {
                                createMode = null
                            }
                        },
                        onSubmitRefinement = { _, _, _ -> },
                        onCancel = { createMode = null }
                    )
                }

                createMode is CreateMode.Refinement -> {
                    CreateNodeScreen(
                        targetType = NodeType.REFINEMENT,
                        parentNode = (createMode as CreateMode.Refinement).parentIdea,
                        onSubmitProblem = { _, _ -> },
                        onSubmitImprovement = { _, _, _ -> },
                        onSubmitRefinement = { title, desc, parentId ->
                            viewModel.createRefinement(title, desc, parentId) {
                                createMode = null
                            }
                        },
                        onCancel = { createMode = null }
                    )
                }

                // Global Search Overlay
                isSearching -> {
                    SearchScreen(
                        searchQuery = searchQuery,
                        onSearchQueryChange = { q -> viewModel.onSearchQueryChange(q) },
                        searchResults = searchResults,
                        onNodeClick = { id ->
                            isSearching = false
                            viewModel.onSelectNode(id)
                        },
                        onVoteToggle = { id -> viewModel.toggleVote(id) }
                    )
                }

                // Node Evolution Detail View
                selectedNodeId != null -> {
                    IdeaDetailScreen(
                        node = selectedNode,
                        treeNodes = selectedNodeTree,
                        currentUser = currentUser,
                        onVoteToggle = { id -> viewModel.toggleVote(id) },
                        onToggleExpand = { id -> viewModel.toggleNodeExpand(id) },
                        onRefineNode = { parentNode ->
                            createMode = CreateMode.Refinement(parentNode)
                        },
                        onEditNode = { nodeToEdit -> editingNode = nodeToEdit },
                        onDeleteNode = { nodeToDelete ->
                            viewModel.deleteNode(nodeToDelete.id) {
                                viewModel.onSelectNode(null)
                            }
                        },
                        onParentClick = { pId -> viewModel.onSelectNode(pId) }
                    )
                }

                // Primary Tab Content
                else -> {
                    when (selectedTab) {
                        LifeNavigationTab.PROBLEMS -> {
                            ProblemsFeedScreen(
                                problems = problems,
                                onProblemClick = { id -> viewModel.onSelectNode(id) },
                                onVoteToggle = { id -> viewModel.toggleVote(id) },
                                onCreateProblemClick = { createMode = CreateMode.Problem }
                            )
                        }
                        LifeNavigationTab.IMPROVEMENTS -> {
                            ImprovementsFeedScreen(
                                improvements = improvements,
                                onImprovementClick = { id -> viewModel.onSelectNode(id) },
                                onVoteToggle = { id -> viewModel.toggleVote(id) },
                                onCreateImprovementClick = { createMode = CreateMode.Improvement() }
                            )
                        }
                        LifeNavigationTab.LEADERBOARD -> {
                            LeaderboardScreen(
                                topProblems = topProblems,
                                topImprovements = topImprovements,
                                topRefiners = topRefiners
                            )
                        }
                        LifeNavigationTab.PROFILE -> {
                            val myNodes = allActiveNodes.filter { it.authorId == currentUser?.id }
                            ProfileScreen(
                                user = currentUser,
                                userScore = currentUserScore,
                                myNodes = myNodes,
                                onNodeClick = { id -> viewModel.onSelectNode(id) },
                                onVoteToggle = { id -> viewModel.toggleVote(id) },
                                onOpenAdminPanel = { showAdminDialog = true },
                                onLogout = { viewModel.logout() }
                            )
                        }
                    }
                }
            }

            // Dialogs
            if (showAdminDialog) {
                AdminManagementDialog(
                    users = allUsers,
                    loginEvents = loginEvents,
                    onCreateStudent = { name, reg, phone, dept, email, password ->
                        viewModel.createStudent(name, reg, phone, dept, email, password)
                    },
                    onApproveStudent = { id ->
                        viewModel.approveStudent(id)
                    },
                    onRejectStudent = { id ->
                        viewModel.rejectStudent(id)
                    },
                    onSuspendStudent = { id ->
                        viewModel.suspendStudent(id)
                    },
                    onRestoreStudent = { id ->
                        viewModel.restoreStudent(id)
                    },
                    onDismiss = { showAdminDialog = false }
                )
            }

            editingNode?.let { node ->
                EditNodeDialog(
                    node = node,
                    onSave = { newTitle, newDesc ->
                        viewModel.editNode(node.id, newTitle, newDesc) {
                            editingNode = null
                        }
                    },
                    onDismiss = { editingNode = null }
                )
            }
        }
    }
}
