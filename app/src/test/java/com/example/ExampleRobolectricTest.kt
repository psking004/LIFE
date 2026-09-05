package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.domain.engine.ScoreEngine
import com.example.domain.engine.TreeEngine
import com.example.domain.engine.ValidationEngine
import com.example.domain.model.IdeaNode
import com.example.domain.model.NodeType
import com.example.domain.model.TreeDisplayNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("LIFE", appName)
  }

  @Test
  fun `score engine creation points are exact`() {
    assertEquals(5.0, ScoreEngine.getCreationScore(NodeType.PROBLEM), 0.001)
    assertEquals(5.0, ScoreEngine.getCreationScore(NodeType.IMPROVEMENT), 0.001)
    assertEquals(10.0, ScoreEngine.getCreationScore(NodeType.REFINEMENT), 0.001)
  }

  @Test
  fun `score engine vote addition and removal are symmetric`() {
    // Normal root node
    val deltaRootAdd = ScoreEngine.calculateVoteAdded(hasParent = false)
    assertEquals(1.0, deltaRootAdd.targetAuthorScoreDelta, 0.001)
    assertEquals(0.0, deltaRootAdd.parentAuthorScoreDelta, 0.001)

    val deltaRootRemove = ScoreEngine.calculateVoteRemoved(hasParent = false)
    assertEquals(-1.0, deltaRootRemove.targetAuthorScoreDelta, 0.001)
    assertEquals(0.0, deltaRootRemove.parentAuthorScoreDelta, 0.001)

    // Child node (e.g. Refinement or Linked Improvement)
    val deltaChildAdd = ScoreEngine.calculateVoteAdded(hasParent = true)
    assertEquals(1.0, deltaChildAdd.targetAuthorScoreDelta, 0.001)
    assertEquals(0.25, deltaChildAdd.parentAuthorScoreDelta, 0.001)

    val deltaChildRemove = ScoreEngine.calculateVoteRemoved(hasParent = true)
    assertEquals(-1.0, deltaChildRemove.targetAuthorScoreDelta, 0.001)
    assertEquals(-0.25, deltaChildRemove.parentAuthorScoreDelta, 0.001)

    // Repeated cycles leave net score at zero
    val netTarget = deltaChildAdd.targetAuthorScoreDelta + deltaChildRemove.targetAuthorScoreDelta
    val netParent = deltaChildAdd.parentAuthorScoreDelta + deltaChildRemove.parentAuthorScoreDelta
    assertEquals(0.0, netTarget, 0.001)
    assertEquals(0.0, netParent, 0.001)
  }

  @Test
  fun `score engine formatScore formats correctly`() {
    assertEquals("5", ScoreEngine.formatScore(5.0))
    assertEquals("10", ScoreEngine.formatScore(10.0))
    assertEquals("1.25", ScoreEngine.formatScore(1.25))
    assertEquals("0.5", ScoreEngine.formatScore(0.5))
  }

  @Test
  fun `node relationship validation conforms strictly to MVP spec`() {
    // Problem as root -> Valid
    assertTrue(ValidationEngine.validateNodeCreation("Valid Problem Title", "Valid description that is at least 20 chars long", NodeType.PROBLEM, null).isValid)
    // Problem with parent -> Invalid
    assertFalse(ValidationEngine.validateNodeCreation("Valid Problem Title", "Valid description that is at least 20 chars long", NodeType.PROBLEM, NodeType.PROBLEM).isValid)
    assertFalse(ValidationEngine.validateNodeCreation("Valid Problem Title", "Valid description that is at least 20 chars long", NodeType.PROBLEM, NodeType.IMPROVEMENT).isValid)

    // Standalone Improvement -> Valid
    assertTrue(ValidationEngine.validateNodeCreation("Valid Improvement Title", "Valid description that is at least 20 chars long", NodeType.IMPROVEMENT, null).isValid)
    // Improvement from Problem -> Valid
    assertTrue(ValidationEngine.validateNodeCreation("Valid Improvement Title", "Valid description that is at least 20 chars long", NodeType.IMPROVEMENT, NodeType.PROBLEM).isValid)
    // Improvement from Improvement or Refinement -> Invalid
    assertFalse(ValidationEngine.validateNodeCreation("Valid Improvement Title", "Valid description that is at least 20 chars long", NodeType.IMPROVEMENT, NodeType.IMPROVEMENT).isValid)
    assertFalse(ValidationEngine.validateNodeCreation("Valid Improvement Title", "Valid description that is at least 20 chars long", NodeType.IMPROVEMENT, NodeType.REFINEMENT).isValid)

    // Refinement with null parent -> Invalid
    assertFalse(ValidationEngine.validateNodeCreation("Valid Refinement Title", "Valid description that is at least 20 chars long", NodeType.REFINEMENT, null).isValid)
    // Refinement from Problem, Improvement, or Refinement -> Valid
    assertTrue(ValidationEngine.validateNodeCreation("Valid Refinement Title", "Valid description that is at least 20 chars long", NodeType.REFINEMENT, NodeType.PROBLEM).isValid)
    assertTrue(ValidationEngine.validateNodeCreation("Valid Refinement Title", "Valid description that is at least 20 chars long", NodeType.REFINEMENT, NodeType.IMPROVEMENT).isValid)
    assertTrue(ValidationEngine.validateNodeCreation("Valid Refinement Title", "Valid description that is at least 20 chars long", NodeType.REFINEMENT, NodeType.REFINEMENT).isValid)
  }

  @Test
  fun `validation engine enforces title and description boundaries`() {
    assertFalse(ValidationEngine.validateTitle("").isValid)
    assertFalse(ValidationEngine.validateTitle("Short").isValid)
    assertFalse(ValidationEngine.validateTitle("   Nine ch  ").isValid)
    assertTrue(ValidationEngine.validateTitle("Ten chars!").isValid)

    assertFalse(ValidationEngine.validateDescription("").isValid)
    assertFalse(ValidationEngine.validateDescription("Short detail").isValid)
    assertTrue(ValidationEngine.validateDescription("This description has more than twenty characters!").isValid)
  }

  @Test
  fun `edit window boundary test`() {
    val now = 1000000000L
    assertTrue(ValidationEngine.canEditNode(createdAt = now - (29 * 60 * 1000L), currentTime = now))
    assertTrue(ValidationEngine.canEditNode(createdAt = now - (30 * 60 * 1000L), currentTime = now))
    assertFalse(ValidationEngine.canEditNode(createdAt = now - (31 * 60 * 1000L), currentTime = now))
  }

  @Test
  fun `deletion rules enforce author ownership and zero children constraint`() {
    // Other user -> rejected
    assertFalse(ValidationEngine.canDeleteNode(authorId = "user_a", currentUserId = "user_b", childCount = 0).isValid)
    // Author with children -> rejected
    assertFalse(ValidationEngine.canDeleteNode(authorId = "user_a", currentUserId = "user_a", childCount = 1).isValid)
    assertFalse(ValidationEngine.canDeleteNode(authorId = "user_a", currentUserId = "user_a", childCount = 3).isValid)
    // Author with 0 children -> allowed
    assertTrue(ValidationEngine.canDeleteNode(authorId = "user_a", currentUserId = "user_a", childCount = 0).isValid)
  }

  @Test
  fun `tree engine flattens 4-level deep hierarchies properly`() {
    val root = IdeaNode(
      id = "p1",
      title = "Campus Cafeteria Rush",
      description = "Long queues during lunch hours",
      type = NodeType.PROBLEM,
      authorId = "u1",
      authorRegisterNumber = "RA001",
      parentId = null,
      votesCount = 10,
      createdAt = 1000L
    )

    val imp1 = IdeaNode(
      id = "i1",
      title = "Pre-order mobile kiosk",
      description = "Allow pre-ordering 15 mins early",
      type = NodeType.IMPROVEMENT,
      authorId = "u2",
      authorRegisterNumber = "RA002",
      parentId = "p1",
      votesCount = 5,
      createdAt = 2000L
    )

    val ref1 = IdeaNode(
      id = "r1",
      title = "QR Code pickup tokens",
      description = "Fast verification using rotating QR",
      type = NodeType.REFINEMENT,
      authorId = "u3",
      authorRegisterNumber = "RA003",
      parentId = "i1",
      votesCount = 8,
      createdAt = 3000L
    )

    val ref2 = IdeaNode(
      id = "r2",
      title = "Offline token caching",
      description = "Cache tokens locally in case WiFi drops",
      type = NodeType.REFINEMENT,
      authorId = "u4",
      authorRegisterNumber = "RA004",
      parentId = "r1",
      votesCount = 3,
      createdAt = 4000L
    )

    val allNodes = listOf(root, imp1, ref1, ref2)
    val expandedAll = setOf("r1")

    val flattened = TreeEngine.flattenTree(root, allNodes, expandedAll)
    assertEquals(3, flattened.size)
    assertEquals("i1", flattened[0].node.id)
    assertEquals(1, flattened[0].depth)
    assertEquals("r1", flattened[1].node.id)
    assertEquals(2, flattened[1].depth)
    assertEquals("r2", flattened[2].node.id)
    assertEquals(3, flattened[2].depth)

    // Collapsing i1 hides r1 and r2
    val collapsedImp1 = setOf("collapsed_i1")
    val flattenedCollapsed = TreeEngine.flattenTree(root, allNodes, collapsedImp1)
    assertEquals(1, flattenedCollapsed.size)
    assertEquals("i1", flattenedCollapsed[0].node.id)
  }

  @Test
  fun `leaderboard tie-breaking logic produces 1, 1, 3 for equal scores`() {
    val scores = listOf(
      com.example.data.local.entities.UserScoreEntity(userId = "u1", registerNumber = "RA001", problemScore = 20.0, problemsCount = 4),
      com.example.data.local.entities.UserScoreEntity(userId = "u2", registerNumber = "RA002", problemScore = 20.0, problemsCount = 4),
      com.example.data.local.entities.UserScoreEntity(userId = "u3", registerNumber = "RA003", problemScore = 15.0, problemsCount = 3)
    )

    var currentRank = 1
    val rows = scores.mapIndexed { index, userScoreEntity ->
      val currentScore = userScoreEntity.problemScore
      val prevScore = if (index > 0) scores[index - 1].problemScore else null
      if (index > 0 && currentScore < (prevScore ?: 0.0)) {
        currentRank = index + 1
      }
      com.example.domain.model.LeaderboardRow(
        rank = currentRank,
        displayRank = "#$currentRank",
        userId = userScoreEntity.userId,
        registerNumber = userScoreEntity.registerNumber,
        score = currentScore,
        count = userScoreEntity.problemsCount
      )
    }

    assertEquals(1, rows[0].rank)
    assertEquals(1, rows[1].rank)
    assertEquals(3, rows[2].rank)
  }
}

