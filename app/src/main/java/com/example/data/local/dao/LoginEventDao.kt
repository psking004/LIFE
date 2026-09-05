package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entities.LoginEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LoginEventDao {
    @Query("SELECT * FROM login_events ORDER BY loginAt DESC")
    fun getAllLoginEvents(): Flow<List<LoginEventEntity>>

    @Query("SELECT * FROM login_events WHERE loginStatus = 'SUCCESS' ORDER BY loginAt DESC LIMIT 50")
    fun getRecentSuccessfulLogins(): Flow<List<LoginEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLoginEvent(event: LoginEventEntity)
}
