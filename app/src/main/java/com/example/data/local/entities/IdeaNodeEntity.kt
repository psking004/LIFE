package com.example.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.domain.model.IdeaNode
import com.example.domain.model.NodeType

@Entity(
    tableName = "idea_nodes",
    indices = [
        Index(value = ["type"]),
        Index(value = ["authorId"]),
        Index(value = ["parentId"]),
        Index(value = ["createdAt"]),
        Index(value = ["isDeleted"])
    ]
)
data class IdeaNodeEntity(
    @PrimaryKey val id: String,
    val type: String, // PROBLEM, IMPROVEMENT, REFINEMENT
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
    val totalScore: Double = 0.0
) {
    fun toDomain(
        isVoted: Boolean = false,
        childrenCount: Int = 0,
        parentTitle: String? = null
    ): IdeaNode = IdeaNode(
        id = id,
        type = NodeType.valueOf(type),
        authorId = authorId,
        authorRegisterNumber = authorRegisterNumber,
        parentId = parentId,
        title = title,
        description = description,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isDeleted = isDeleted,
        deletedAt = deletedAt,
        votesCount = votesCount,
        directScore = directScore,
        totalScore = totalScore,
        isVotedByCurrentUser = isVoted,
        childrenCount = childrenCount,
        parentTitle = parentTitle
    )
}
