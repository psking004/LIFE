package com.example.data.repository

import com.example.data.local.AppDatabase
import com.example.data.local.entities.IdeaNodeEntity
import com.example.data.local.entities.UserEntity
import com.example.data.local.entities.UserScoreEntity
import com.example.data.local.entities.VoteEntity
import com.example.domain.engine.ScoreEngine
import com.example.domain.engine.ValidationEngine
import com.example.domain.model.IdeaNode
import com.example.domain.model.LeaderboardRow
import com.example.domain.model.NodeType
import com.example.domain.model.User
import com.example.domain.model.UserScore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

class LifeRepository(
    private val database: AppDatabase,
    private val sessionManager: AuthSessionManager,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val supabaseSyncManager: com.example.data.remote.SupabaseSyncManager = com.example.data.remote.SupabaseSyncManager(database)
) {
    private val userDao = database.userDao()
    private val nodeDao = database.ideaNodeDao()
    private val voteDao = database.voteDao()
    private val scoreDao = database.userScoreDao()
    private val loginEventDao = database.loginEventDao()

    val authRepository: AuthRepository = AuthRepository(
        userDao = userDao,
        loginEventDao = loginEventDao,
        sessionManager = sessionManager
    )

    suspend fun initializeDatabase() = withContext(dispatcher) {
        // Ensure baseline demo and pilot users exist
        for (user in SeedData.initialUsers) {
            val existing = userDao.getUserByRegisterNumber(user.registerNumber)
            if (existing == null) {
                userDao.insertUser(user)
            } else if (user.registerNumber in listOf("23CSE001", "23CSE002", "ADMIN01") && existing.passwordHash == "admin123" && user.passwordHash == "pass123") {
                // Update demo student 1 password if previously seeded with admin123
                userDao.updatePassword(existing.id, user.passwordHash)
            }
        }
        val existingProb = nodeDao.getNodeById(SeedData.nodeProblem1Id)
        if (existingProb == null) {
            nodeDao.insertNodes(SeedData.initialNodes)
            voteDao.insertVotes(SeedData.initialVotes)
            scoreDao.insertUserScores(SeedData.initialScores)
        }
        // Restore active session if valid and account is ACTIVE
        authRepository.restoreSessionIfValid()

        // Attempt background sync with Supabase remote backend
        try {
            supabaseSyncManager.syncFromRemote()
        } catch (ignored: Exception) {
        }
    }

    // --- Authentication & Session ---

    val currentUserId: Flow<String?> = authRepository.currentUserId

    fun observeCurrentUser(): Flow<User?> = authRepository.observeCurrentUser()

    suspend fun restoreSessionIfValid(): Boolean = authRepository.restoreSessionIfValid()

    suspend fun requestLoginOtp(registerNumber: String, password: String): Result<OtpChallenge> {
        return authRepository.requestLoginOtp(registerNumber, password)
    }

    suspend fun verifyOtp(challenge: OtpChallenge, otpCode: String): Result<User> {
        return authRepository.verifyOtp(challenge, otpCode)
    }

    suspend fun login(registerNumber: String, password: String): Result<User> {
        return authRepository.login(registerNumber, password)
    }

    suspend fun setupFirstLoginPassword(userId: String, newPassword: String): Result<Unit> {
        return authRepository.setupFirstLoginPassword(userId, newPassword)
    }

    suspend fun logout() {
        authRepository.logout()
    }

    fun observeAllLoginEvents(): Flow<List<com.example.domain.model.LoginEvent>> = authRepository.observeAllLoginEvents()

    fun observeRecentSuccessfulLogins(): Flow<List<com.example.domain.model.LoginEvent>> = authRepository.observeRecentSuccessfulLogins()

    // --- Idea Nodes Queries & Feeds ---

    fun getProblems(): Flow<List<IdeaNode>> {
        return combine(
            nodeDao.getProblems(),
            sessionManager.currentUserId,
            nodeDao.getAllActiveNodes()
        ) { problems, currentUserId, allActive ->
            val votedNodeIds = if (currentUserId != null) {
                voteDao.getVotedNodeIdsByUser(currentUserId).firstOrNull() ?: emptyList()
            } else emptyList()

            val childrenMap = allActive.filter { it.parentId != null }.groupBy { it.parentId!! }

            problems.map { entity ->
                val isVoted = votedNodeIds.contains(entity.id)
                val children = childrenMap[entity.id]?.size ?: 0
                entity.toDomain(isVoted = isVoted, childrenCount = children)
            }
        }.flowOn(dispatcher)
    }

    fun getImprovements(): Flow<List<IdeaNode>> {
        return combine(
            nodeDao.getImprovements(),
            sessionManager.currentUserId,
            nodeDao.getAllActiveNodes()
        ) { improvements, currentUserId, allActive ->
            val votedNodeIds = if (currentUserId != null) {
                voteDao.getVotedNodeIdsByUser(currentUserId).firstOrNull() ?: emptyList()
            } else emptyList()

            val childrenMap = allActive.filter { it.parentId != null }.groupBy { it.parentId!! }
            val nodesById = allActive.associateBy { it.id }

            improvements.map { entity ->
                val isVoted = votedNodeIds.contains(entity.id)
                val children = childrenMap[entity.id]?.size ?: 0
                val parentTitle = entity.parentId?.let { nodesById[it]?.title }
                entity.toDomain(isVoted = isVoted, childrenCount = children, parentTitle = parentTitle)
            }
        }.flowOn(dispatcher)
    }

    fun observeNodeById(nodeId: String): Flow<IdeaNode?> {
        return combine(
            nodeDao.observeNodeById(nodeId),
            sessionManager.currentUserId,
            nodeDao.getAllActiveNodes()
        ) { nodeEntity, currentUserId, allActive ->
            if (nodeEntity == null) return@combine null

            val isVoted = if (currentUserId != null) {
                voteDao.getVote(currentUserId, nodeId) != null
            } else false

            val children = allActive.count { it.parentId == nodeId && !it.isDeleted }
            val parentTitle = nodeEntity.parentId?.let { pId ->
                allActive.find { it.id == pId }?.title
            }

            nodeEntity.toDomain(isVoted = isVoted, childrenCount = children, parentTitle = parentTitle)
        }.flowOn(dispatcher)
    }

    fun getAllActiveNodes(): Flow<List<IdeaNode>> {
        return combine(
            nodeDao.getAllActiveNodes(),
            sessionManager.currentUserId
        ) { nodes, currentUserId ->
            val votedNodeIds = if (currentUserId != null) {
                voteDao.getVotedNodeIdsByUser(currentUserId).firstOrNull() ?: emptyList()
            } else emptyList()

            val childrenMap = nodes.filter { it.parentId != null }.groupBy { it.parentId!! }
            val nodesById = nodes.associateBy { it.id }

            nodes.map { entity ->
                val isVoted = votedNodeIds.contains(entity.id)
                val children = childrenMap[entity.id]?.size ?: 0
                val parentTitle = entity.parentId?.let { nodesById[it]?.title }
                entity.toDomain(isVoted = isVoted, childrenCount = children, parentTitle = parentTitle)
            }
        }.flowOn(dispatcher)
    }

    fun getNodesByAuthor(authorId: String): Flow<List<IdeaNode>> {
        return combine(
            nodeDao.getNodesByAuthor(authorId),
            sessionManager.currentUserId,
            nodeDao.getAllActiveNodes()
        ) { nodes, currentUserId, allActive ->
            val votedNodeIds = if (currentUserId != null) {
                voteDao.getVotedNodeIdsByUser(currentUserId).firstOrNull() ?: emptyList()
            } else emptyList()
            val childrenMap = allActive.filter { it.parentId != null }.groupBy { it.parentId!! }
            nodes.map { entity ->
                entity.toDomain(
                    isVoted = votedNodeIds.contains(entity.id),
                    childrenCount = childrenMap[entity.id]?.size ?: 0
                )
            }
        }.flowOn(dispatcher)
    }

    fun searchNodes(query: String): Flow<List<IdeaNode>> {
        return combine(
            nodeDao.searchNodes(query.trim()),
            sessionManager.currentUserId,
            nodeDao.getAllActiveNodes()
        ) { nodes, currentUserId, allActive ->
            val votedNodeIds = if (currentUserId != null) {
                voteDao.getVotedNodeIdsByUser(currentUserId).firstOrNull() ?: emptyList()
            } else emptyList()
            val childrenMap = allActive.filter { it.parentId != null }.groupBy { it.parentId!! }
            val nodesById = allActive.associateBy { it.id }
            nodes.map { entity ->
                entity.toDomain(
                    isVoted = votedNodeIds.contains(entity.id),
                    childrenCount = childrenMap[entity.id]?.size ?: 0,
                    parentTitle = entity.parentId?.let { nodesById[it]?.title }
                )
            }
        }.flowOn(dispatcher)
    }

    // --- Mutations ---

    suspend fun createNode(
        title: String,
        description: String,
        type: NodeType,
        parentId: String? = null
    ): Result<IdeaNode> = withContext(dispatcher) {
        val currentUserId = sessionManager.currentUserId.firstOrNull()
            ?: return@withContext Result.failure(IllegalStateException("Must be logged in"))

        val currentUser = userDao.getUserById(currentUserId)
            ?: return@withContext Result.failure(IllegalStateException("User not found"))

        if (currentUser.isSuspended) {
            return@withContext Result.failure(IllegalStateException("Account suspended. Cannot create content."))
        }

        // Validate parent if provided
        var parentNode: IdeaNodeEntity? = null
        if (parentId != null) {
            parentNode = nodeDao.getNodeById(parentId)
            if (parentNode == null || parentNode.isDeleted) {
                return@withContext Result.failure(IllegalArgumentException("Parent idea no longer exists"))
            }
        }

        val parentType = parentNode?.let { NodeType.valueOf(it.type) }
        val validation = ValidationEngine.validateNodeCreation(title, description, type, parentType)
        if (!validation.isValid) {
            return@withContext Result.failure(IllegalArgumentException((validation as ValidationEngine.ValidationResult.Invalid).reason))
        }

        val creationScore = ScoreEngine.getCreationScore(type)
        val now = System.currentTimeMillis()
        val newNodeId = "node_" + UUID.randomUUID().toString().take(8)

        val entity = IdeaNodeEntity(
            id = newNodeId,
            type = type.name,
            authorId = currentUser.id,
            authorRegisterNumber = currentUser.registerNumber,
            parentId = parentId,
            title = title.trim(),
            description = description.trim(),
            createdAt = now,
            updatedAt = now,
            votesCount = 0,
            directScore = creationScore,
            totalScore = creationScore
        )

        nodeDao.insertNode(entity)

        // Update user score
        val currentScore = scoreDao.getUserScore(currentUser.id) ?: UserScoreEntity(
            userId = currentUser.id,
            registerNumber = currentUser.registerNumber
        )

        val updatedScore = when (type) {
            NodeType.PROBLEM -> currentScore.copy(
                problemScore = currentScore.problemScore + ScoreEngine.PROBLEM_CREATION_SCORE,
                totalScore = currentScore.totalScore + ScoreEngine.PROBLEM_CREATION_SCORE,
                problemsCount = currentScore.problemsCount + 1
            )
            NodeType.IMPROVEMENT -> currentScore.copy(
                improvementScore = currentScore.improvementScore + ScoreEngine.IMPROVEMENT_CREATION_SCORE,
                totalScore = currentScore.totalScore + ScoreEngine.IMPROVEMENT_CREATION_SCORE,
                improvementsCount = currentScore.improvementsCount + 1
            )
            NodeType.REFINEMENT -> currentScore.copy(
                refinementScore = currentScore.refinementScore + ScoreEngine.REFINEMENT_CREATION_SCORE,
                totalScore = currentScore.totalScore + ScoreEngine.REFINEMENT_CREATION_SCORE,
                refinementsCount = currentScore.refinementsCount + 1
            )
        }
        scoreDao.insertUserScore(updatedScore)

        try {
            supabaseSyncManager.pushNode(entity)
        } catch (ignored: Exception) {
        }

        Result.success(entity.toDomain())
    }

    suspend fun toggleVote(nodeId: String): Result<Boolean> = withContext(dispatcher) {
        val currentUserId = sessionManager.currentUserId.firstOrNull()
            ?: return@withContext Result.failure(IllegalStateException("Must be logged in to vote"))

        val currentUser = userDao.getUserById(currentUserId)
            ?: return@withContext Result.failure(IllegalStateException("User not found"))

        if (currentUser.isSuspended) {
            return@withContext Result.failure(IllegalStateException("Account suspended. Cannot vote."))
        }

        val node = nodeDao.getNodeById(nodeId)
            ?: return@withContext Result.failure(IllegalArgumentException("Node not found"))

        if (node.isDeleted) {
            return@withContext Result.failure(IllegalArgumentException("Cannot vote on deleted content"))
        }

        val existingVote = voteDao.getVote(currentUserId, nodeId)
        val isUpvoting = existingVote == null
        val nodeType = NodeType.valueOf(node.type)

        if (isUpvoting) {
            // Add vote
            val voteEntity = VoteEntity(
                id = "vote_" + UUID.randomUUID().toString().take(8),
                userId = currentUserId,
                nodeId = nodeId,
                createdAt = System.currentTimeMillis()
            )
            voteDao.insertVote(voteEntity)

            val newVotesCount = node.votesCount + 1
            val newDirectScore = node.directScore + ScoreEngine.VOTE_RECEIVED_DIRECT_SCORE
            val newTotalScore = node.totalScore + ScoreEngine.VOTE_RECEIVED_DIRECT_SCORE
            nodeDao.updateNodeScores(nodeId, newVotesCount, newDirectScore, newTotalScore)

            // Update Author Score
            val authorScore = scoreDao.getUserScore(node.authorId) ?: UserScoreEntity(
                userId = node.authorId,
                registerNumber = node.authorRegisterNumber
            )
            val updatedAuthorScore = when (nodeType) {
                NodeType.PROBLEM -> authorScore.copy(
                    problemScore = authorScore.problemScore + 1.0,
                    totalScore = authorScore.totalScore + 1.0
                )
                NodeType.IMPROVEMENT -> authorScore.copy(
                    improvementScore = authorScore.improvementScore + 1.0,
                    totalScore = authorScore.totalScore + 1.0
                )
                NodeType.REFINEMENT -> authorScore.copy(
                    refinementScore = authorScore.refinementScore + 1.0,
                    totalScore = authorScore.totalScore + 1.0
                )
            }
            scoreDao.insertUserScore(updatedAuthorScore)

            // Direct Parent Credit (0.25 points to direct parent author)
            if (node.parentId != null) {
                val parentNode = nodeDao.getNodeById(node.parentId)
                if (parentNode != null && !parentNode.isDeleted) {
                    val parentAuthorScore = scoreDao.getUserScore(parentNode.authorId) ?: UserScoreEntity(
                        userId = parentNode.authorId,
                        registerNumber = parentNode.authorRegisterNumber
                    )
                    val parentType = NodeType.valueOf(parentNode.type)
                    val updatedParentScore = when (parentType) {
                        NodeType.PROBLEM -> parentAuthorScore.copy(
                            problemScore = parentAuthorScore.problemScore + ScoreEngine.DIRECT_PARENT_VOTE_CREDIT,
                            totalScore = parentAuthorScore.totalScore + ScoreEngine.DIRECT_PARENT_VOTE_CREDIT
                        )
                        NodeType.IMPROVEMENT -> parentAuthorScore.copy(
                            improvementScore = parentAuthorScore.improvementScore + ScoreEngine.DIRECT_PARENT_VOTE_CREDIT,
                            totalScore = parentAuthorScore.totalScore + ScoreEngine.DIRECT_PARENT_VOTE_CREDIT
                        )
                        NodeType.REFINEMENT -> parentAuthorScore.copy(
                            refinementScore = parentAuthorScore.refinementScore + ScoreEngine.DIRECT_PARENT_VOTE_CREDIT,
                            totalScore = parentAuthorScore.totalScore + ScoreEngine.DIRECT_PARENT_VOTE_CREDIT
                        )
                    }
                    scoreDao.insertUserScore(updatedParentScore)
                }
            }

            try {
                supabaseSyncManager.pushVote(voteEntity)
            } catch (ignored: Exception) {
            }

            Result.success(true)
        } else {
            // Remove vote
            voteDao.deleteVote(currentUserId, nodeId)

            try {
                supabaseSyncManager.deleteVote(nodeId, currentUserId)
            } catch (ignored: Exception) {
            }

            val newVotesCount = maxOf(0, node.votesCount - 1)
            val newDirectScore = maxOf(0.0, node.directScore - ScoreEngine.VOTE_RECEIVED_DIRECT_SCORE)
            val newTotalScore = maxOf(0.0, node.totalScore - ScoreEngine.VOTE_RECEIVED_DIRECT_SCORE)
            nodeDao.updateNodeScores(nodeId, newVotesCount, newDirectScore, newTotalScore)

            // Decrement Author Score
            val authorScore = scoreDao.getUserScore(node.authorId)
            if (authorScore != null) {
                val updatedAuthorScore = when (nodeType) {
                    NodeType.PROBLEM -> authorScore.copy(
                        problemScore = maxOf(0.0, authorScore.problemScore - 1.0),
                        totalScore = maxOf(0.0, authorScore.totalScore - 1.0)
                    )
                    NodeType.IMPROVEMENT -> authorScore.copy(
                        improvementScore = maxOf(0.0, authorScore.improvementScore - 1.0),
                        totalScore = maxOf(0.0, authorScore.totalScore - 1.0)
                    )
                    NodeType.REFINEMENT -> authorScore.copy(
                        refinementScore = maxOf(0.0, authorScore.refinementScore - 1.0),
                        totalScore = maxOf(0.0, authorScore.totalScore - 1.0)
                    )
                }
                scoreDao.insertUserScore(updatedAuthorScore)
            }

            // Decrement Parent Credit
            if (node.parentId != null) {
                val parentNode = nodeDao.getNodeById(node.parentId)
                if (parentNode != null && !parentNode.isDeleted) {
                    val parentAuthorScore = scoreDao.getUserScore(parentNode.authorId)
                    if (parentAuthorScore != null) {
                        val parentType = NodeType.valueOf(parentNode.type)
                        val updatedParentScore = when (parentType) {
                            NodeType.PROBLEM -> parentAuthorScore.copy(
                                problemScore = maxOf(0.0, parentAuthorScore.problemScore - ScoreEngine.DIRECT_PARENT_VOTE_CREDIT),
                                totalScore = maxOf(0.0, parentAuthorScore.totalScore - ScoreEngine.DIRECT_PARENT_VOTE_CREDIT)
                            )
                            NodeType.IMPROVEMENT -> parentAuthorScore.copy(
                                improvementScore = maxOf(0.0, parentAuthorScore.improvementScore - ScoreEngine.DIRECT_PARENT_VOTE_CREDIT),
                                totalScore = maxOf(0.0, parentAuthorScore.totalScore - ScoreEngine.DIRECT_PARENT_VOTE_CREDIT)
                            )
                            NodeType.REFINEMENT -> parentAuthorScore.copy(
                                refinementScore = maxOf(0.0, parentAuthorScore.refinementScore - ScoreEngine.DIRECT_PARENT_VOTE_CREDIT),
                                totalScore = maxOf(0.0, parentAuthorScore.totalScore - ScoreEngine.DIRECT_PARENT_VOTE_CREDIT)
                            )
                        }
                        scoreDao.insertUserScore(updatedParentScore)
                    }
                }
            }

            Result.success(false)
        }
    }

    suspend fun editNode(nodeId: String, newTitle: String, newDescription: String): Result<Unit> = withContext(dispatcher) {
        val currentUserId = sessionManager.currentUserId.firstOrNull()
            ?: return@withContext Result.failure(IllegalStateException("Must be logged in"))

        val node = nodeDao.getNodeById(nodeId)
            ?: return@withContext Result.failure(IllegalArgumentException("Node not found"))

        if (node.authorId != currentUserId) {
            return@withContext Result.failure(IllegalStateException("You can only edit your own ideas"))
        }

        if (!ValidationEngine.canEditNode(node.createdAt)) {
            return@withContext Result.failure(IllegalStateException("Edit window has expired (30 minutes)"))
        }

        val titleVal = ValidationEngine.validateTitle(newTitle)
        if (!titleVal.isValid) return@withContext Result.failure(IllegalArgumentException((titleVal as ValidationEngine.ValidationResult.Invalid).reason))

        val descVal = ValidationEngine.validateDescription(newDescription)
        if (!descVal.isValid) return@withContext Result.failure(IllegalArgumentException((descVal as ValidationEngine.ValidationResult.Invalid).reason))

        nodeDao.updateNodeContent(nodeId, newTitle.trim(), newDescription.trim(), System.currentTimeMillis())
        Result.success(Unit)
    }

    suspend fun deleteNode(nodeId: String): Result<Unit> = withContext(dispatcher) {
        val currentUserId = sessionManager.currentUserId.firstOrNull()
            ?: return@withContext Result.failure(IllegalStateException("Must be logged in"))

        val currentUser = userDao.getUserById(currentUserId)
            ?: return@withContext Result.failure(IllegalStateException("User not found"))

        val node = nodeDao.getNodeById(nodeId)
            ?: return@withContext Result.failure(IllegalArgumentException("Node not found"))

        val childrenCount = nodeDao.getChildrenCount(nodeId)

        val check = ValidationEngine.canDeleteNode(node.authorId, currentUserId, childrenCount)
        if (!check.isValid && !currentUser.isAdmin) {
            return@withContext Result.failure(IllegalStateException((check as ValidationEngine.ValidationResult.Invalid).reason))
        }

        nodeDao.softDeleteNode(nodeId, System.currentTimeMillis())

        // Reversal of creation score
        val creationScore = ScoreEngine.getCreationScore(NodeType.valueOf(node.type))
        val authorScore = scoreDao.getUserScore(node.authorId)
        if (authorScore != null) {
            val updated = when (NodeType.valueOf(node.type)) {
                NodeType.PROBLEM -> authorScore.copy(
                    problemScore = maxOf(0.0, authorScore.problemScore - creationScore),
                    totalScore = maxOf(0.0, authorScore.totalScore - creationScore),
                    problemsCount = maxOf(0, authorScore.problemsCount - 1)
                )
                NodeType.IMPROVEMENT -> authorScore.copy(
                    improvementScore = maxOf(0.0, authorScore.improvementScore - creationScore),
                    totalScore = maxOf(0.0, authorScore.totalScore - creationScore),
                    improvementsCount = maxOf(0, authorScore.improvementsCount - 1)
                )
                NodeType.REFINEMENT -> authorScore.copy(
                    refinementScore = maxOf(0.0, authorScore.refinementScore - creationScore),
                    totalScore = maxOf(0.0, authorScore.totalScore - creationScore),
                    refinementsCount = maxOf(0, authorScore.refinementsCount - 1)
                )
            }
            scoreDao.insertUserScore(updated)
        }

        Result.success(Unit)
    }

    // --- Profile & Leaderboards ---

    fun observeUserScore(userId: String): Flow<UserScore?> {
        return scoreDao.observeUserScore(userId).map { it?.toDomain() }.flowOn(dispatcher)
    }

    fun getAllUserScores(): Flow<List<UserScore>> {
        return scoreDao.getAllScores().map { list -> list.map { it.toDomain() } }.flowOn(dispatcher)
    }

    fun getTopOverallContributors(): Flow<List<LeaderboardRow>> {
        return scoreDao.getAllScores().map { list ->
            buildLeaderboardRows(list) { it.totalScore to (it.problemsCount + it.improvementsCount + it.refinementsCount) }
        }.flowOn(dispatcher)
    }

    fun getTopProblemContributors(): Flow<List<LeaderboardRow>> {
        return scoreDao.getTopProblemContributors().map { list ->
            buildLeaderboardRows(list) { it.problemScore to it.problemsCount }
        }.flowOn(dispatcher)
    }

    fun getTopImprovementContributors(): Flow<List<LeaderboardRow>> {
        return scoreDao.getTopImprovementContributors().map { list ->
            buildLeaderboardRows(list) { it.improvementScore to it.improvementsCount }
        }.flowOn(dispatcher)
    }

    fun getTopRefiners(): Flow<List<LeaderboardRow>> {
        return scoreDao.getTopRefiners().map { list ->
            buildLeaderboardRows(list) { it.refinementScore to it.refinementsCount }
        }.flowOn(dispatcher)
    }

    private fun buildLeaderboardRows(
        scores: List<UserScoreEntity>,
        scoreSelector: (UserScoreEntity) -> Pair<Double, Int>
    ): List<LeaderboardRow> {
        val sorted = scores.sortedWith(
            compareByDescending<UserScoreEntity> { scoreSelector(it).first }
                .thenByDescending { scoreSelector(it).second }
        )

        var currentRank = 1
        return sorted.mapIndexed { index, userScoreEntity ->
            val currentScore = scoreSelector(userScoreEntity).first
            val prevScore = if (index > 0) scoreSelector(sorted[index - 1]).first else null

            if (index > 0 && currentScore < (prevScore ?: 0.0)) {
                currentRank = index + 1
            }

            LeaderboardRow(
                rank = currentRank,
                displayRank = "#$currentRank",
                userId = userScoreEntity.userId,
                registerNumber = userScoreEntity.registerNumber,
                score = currentScore,
                count = scoreSelector(userScoreEntity).second
            )
        }
    }

    // --- Admin Operations ---

    fun getAllUsers(): Flow<List<User>> {
        return userDao.getAllUsers().map { list -> list.map { it.toDomain() } }.flowOn(dispatcher)
    }

    suspend fun createStudentUser(
        adminUserId: String,
        name: String,
        registerNumber: String,
        phoneNumber: String,
        department: String,
        initialPassword: String = "pass123"
    ): Result<User> = withContext(dispatcher) {
        val result = authRepository.createStudentAccount(
            adminUserId = adminUserId,
            name = name,
            registerNumber = registerNumber,
            phoneNumber = phoneNumber,
            department = department,
            initialPassword = initialPassword
        )
        result.onSuccess { user ->
            val scoreEntity = UserScoreEntity(
                userId = user.id,
                registerNumber = user.registerNumber
            )
            scoreDao.insertUserScore(scoreEntity)
        }
        result
    }

    suspend fun approveStudentAccount(adminUserId: String, targetUserId: String): Result<Unit> {
        return authRepository.approveStudentAccount(adminUserId, targetUserId)
    }

    suspend fun rejectStudentAccount(adminUserId: String, targetUserId: String): Result<Unit> {
        return authRepository.rejectStudentAccount(adminUserId, targetUserId)
    }

    suspend fun suspendStudentAccount(adminUserId: String, targetUserId: String): Result<Unit> {
        return authRepository.suspendStudentAccount(adminUserId, targetUserId)
    }

    suspend fun restoreStudentAccount(adminUserId: String, targetUserId: String): Result<Unit> {
        return authRepository.restoreStudentAccount(adminUserId, targetUserId)
    }

    suspend fun toggleUserSuspension(userId: String, suspend: Boolean): Result<Unit> = withContext(dispatcher) {
        userDao.setSuspended(userId, suspend)
        Result.success(Unit)
    }
}
