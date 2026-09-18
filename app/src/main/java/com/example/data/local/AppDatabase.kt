package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

        // Migration to remove the legacy `passwordHash` column from `users` table.
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create new temporary table without passwordHash
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `users_new` (
                      `id` TEXT NOT NULL, `authUserId` TEXT, `registerNumber` TEXT NOT NULL, `name` TEXT NOT NULL,
                      `phoneNumber` TEXT NOT NULL, `department` TEXT NOT NULL, `email` TEXT NOT NULL,
                      `emailVerified` INTEGER NOT NULL, `accountStatus` TEXT NOT NULL, `isFirstLogin` INTEGER NOT NULL,
                      `isSuspended` INTEGER NOT NULL, `isAdmin` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL,
                      `approvedAt` INTEGER, `approvedBy` TEXT, PRIMARY KEY(`id`)
                    )
                """.trimIndent())

                // Copy data from old table, skipping the passwordHash column
                db.execSQL("""
                    INSERT INTO users_new (id, authUserId, registerNumber, name, phoneNumber, department, email, emailVerified, accountStatus, isFirstLogin, isSuspended, isAdmin, createdAt, approvedAt, approvedBy)
                    SELECT id, authUserId, registerNumber, name, phoneNumber, department, email, emailVerified, accountStatus, isFirstLogin, isSuspended, isAdmin, createdAt, approvedAt, approvedBy FROM users
                """.trimIndent())

                // Drop old table and rename new
                db.execSQL("DROP TABLE users")
                db.execSQL("ALTER TABLE users_new RENAME TO users")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "life_database.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
