package com.example.domain.model

data class IdeaNode(
    val id: String,
    val type: NodeType,
    val authorId: String,
    val authorRegisterNumber: String,
    val parentId: String? = null,
    val title: String,
    val description: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val votesCount: Int = 0,
    val directScore: Double = 0.0,
    val totalScore: Double = 0.0,
    val isVotedByCurrentUser: Boolean = false,
    val childrenCount: Int = 0,
    val parentTitle: String? = null
)
