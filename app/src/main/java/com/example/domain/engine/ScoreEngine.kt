package com.example.domain.engine

import com.example.domain.model.NodeType

object ScoreEngine {
    const val PROBLEM_CREATION_SCORE = 5.0
    const val IMPROVEMENT_CREATION_SCORE = 5.0
    const val REFINEMENT_CREATION_SCORE = 10.0

    const val VOTE_RECEIVED_DIRECT_SCORE = 1.0
    const val DIRECT_PARENT_VOTE_CREDIT = 0.25

    fun getCreationScore(type: NodeType): Double {
        return when (type) {
            NodeType.PROBLEM -> PROBLEM_CREATION_SCORE
            NodeType.IMPROVEMENT -> IMPROVEMENT_CREATION_SCORE
            NodeType.REFINEMENT -> REFINEMENT_CREATION_SCORE
        }
    }

    /**
     * Calculates vote score changes.
     * When a node receives a vote:
     * - Node author gets +1.0
     * - Direct parent author (if parent exists) gets +0.25
     * - Grandparents receive 0.0 (no recursive propagation)
     */
    data class VoteScoreDelta(
        val targetAuthorScoreDelta: Double,
        val parentAuthorScoreDelta: Double
    )

    fun calculateVoteAdded(hasParent: Boolean): VoteScoreDelta {
        return VoteScoreDelta(
            targetAuthorScoreDelta = VOTE_RECEIVED_DIRECT_SCORE,
            parentAuthorScoreDelta = if (hasParent) DIRECT_PARENT_VOTE_CREDIT else 0.0
        )
    }

    fun calculateVoteRemoved(hasParent: Boolean): VoteScoreDelta {
        return VoteScoreDelta(
            targetAuthorScoreDelta = -VOTE_RECEIVED_DIRECT_SCORE,
            parentAuthorScoreDelta = if (hasParent) -DIRECT_PARENT_VOTE_CREDIT else 0.0
        )
    }

    fun formatScore(score: Double): String {
        return if (score % 1.0 == 0.0) {
            score.toLong().toString()
        } else {
            String.format(java.util.Locale.US, "%.2f", score).trimEnd('0').trimEnd('.')
        }
    }
}
