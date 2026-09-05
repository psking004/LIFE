package com.example.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.domain.model.Vote

@Entity(
    tableName = "votes",
    indices = [
        Index(value = ["userId", "nodeId"], unique = true),
        Index(value = ["nodeId"])
    ]
)
data class VoteEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val nodeId: String,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toDomain(): Vote = Vote(
        id = id,
        userId = userId,
        nodeId = nodeId,
        createdAt = createdAt
    )
}
