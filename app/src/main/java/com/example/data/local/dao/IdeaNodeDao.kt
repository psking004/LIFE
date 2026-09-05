package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.entities.IdeaNodeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface IdeaNodeDao {
    @Query("SELECT * FROM idea_nodes WHERE isDeleted = 0 ORDER BY createdAt DESC")
    fun getAllActiveNodes(): Flow<List<IdeaNodeEntity>>

    @Query("SELECT * FROM idea_nodes WHERE type = 'PROBLEM' AND isDeleted = 0 ORDER BY createdAt DESC")
    fun getProblems(): Flow<List<IdeaNodeEntity>>

    @Query("SELECT * FROM idea_nodes WHERE type = 'IMPROVEMENT' AND isDeleted = 0 ORDER BY createdAt DESC")
    fun getImprovements(): Flow<List<IdeaNodeEntity>>

    @Query("SELECT * FROM idea_nodes WHERE id = :id AND isDeleted = 0 LIMIT 1")
    fun observeNodeById(id: String): Flow<IdeaNodeEntity?>

    @Query("SELECT * FROM idea_nodes WHERE id = :id LIMIT 1")
    suspend fun getNodeById(id: String): IdeaNodeEntity?

    @Query("SELECT * FROM idea_nodes WHERE parentId = :parentId AND isDeleted = 0 ORDER BY createdAt ASC")
    fun getChildrenOf(parentId: String): Flow<List<IdeaNodeEntity>>

    @Query("SELECT * FROM idea_nodes WHERE parentId = :parentId AND isDeleted = 0")
    suspend fun getChildrenOfSync(parentId: String): List<IdeaNodeEntity>

    @Query("SELECT COUNT(*) FROM idea_nodes WHERE parentId = :parentId AND isDeleted = 0")
    suspend fun getChildrenCount(parentId: String): Int

    @Query("SELECT * FROM idea_nodes WHERE authorId = :authorId AND isDeleted = 0 ORDER BY createdAt DESC")
    fun getNodesByAuthor(authorId: String): Flow<List<IdeaNodeEntity>>

    @Query("""
        SELECT * FROM idea_nodes 
        WHERE isDeleted = 0 AND (title LIKE '%' || :query || '%' OR description LIKE '%' || :query || '%')
        ORDER BY createdAt DESC
    """)
    fun searchNodes(query: String): Flow<List<IdeaNodeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNode(node: IdeaNodeEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNodes(nodes: List<IdeaNodeEntity>)

    @Update
    suspend fun updateNode(node: IdeaNodeEntity)

    @Query("UPDATE idea_nodes SET title = :title, description = :description, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateNodeContent(id: String, title: String, description: String, updatedAt: Long)

    @Query("UPDATE idea_nodes SET isDeleted = 1, deletedAt = :deletedAt WHERE id = :id")
    suspend fun softDeleteNode(id: String, deletedAt: Long)

    @Query("UPDATE idea_nodes SET votesCount = :votesCount, directScore = :directScore, totalScore = :totalScore WHERE id = :id")
    suspend fun updateNodeScores(id: String, votesCount: Int, directScore: Double, totalScore: Double)
}
