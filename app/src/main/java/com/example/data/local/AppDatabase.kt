package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.local.dao.IdeaNodeDao
import com.example.data.local.dao.LoginEventDao
import com.example.data.local.dao.UserDao
import com.example.data.local.dao.UserScoreDao
import com.example.data.local.dao.VoteDao
import com.example.data.local.entities.IdeaNodeEntity
import com.example.data.local.entities.LoginEventEntity
import com.example.data.local.entities.UserEntity
import com.example.data.local.entities.UserScoreEntity
import com.example.data.local.entities.VoteEntity

@Database(
    entities = [
        UserEntity::class,
        IdeaNodeEntity::class,
        VoteEntity::class,
        UserScoreEntity::class,
        LoginEventEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun ideaNodeDao(): IdeaNodeDao
    abstract fun voteDao(): VoteDao
    abstract fun userScoreDao(): UserScoreDao
    abstract fun loginEventDao(): LoginEventDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "life_database.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
