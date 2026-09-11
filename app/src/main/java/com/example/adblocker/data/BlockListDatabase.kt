package com.example.adblocker.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "blocked_numbers")
data class BlockedNumber(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "number") val number: String
)

@Dao
interface BlockedNumberDao {
    @Query("SELECT * FROM blocked_numbers")
    fun getAll(): List<BlockedNumber>

    @Query("SELECT * FROM blocked_numbers WHERE number = :n LIMIT 1")
    fun findByNumber(n: String): BlockedNumber?

    @Insert
    suspend fun insert(b: BlockedNumber)

    @Delete
    suspend fun delete(b: BlockedNumber)
}

@Database(entities = [BlockedNumber::class], version = 1)
abstract class BlockListDatabase : RoomDatabase() {
    abstract fun blockedNumberDao(): BlockedNumberDao

    companion object {
        @Volatile
        private var INSTANCE: BlockListDatabase? = null

        fun getInstance(context: Context): BlockListDatabase {
            return INSTANCE ?: synchronized(this) {
                val db = Room.databaseBuilder(
                    context.applicationContext,
                    BlockListDatabase::class.java,
                    "blocklist.db"
                )
                    .allowMainThreadQueries() // 本地小库，便于服务内同步读取缓存
                    .build()
                INSTANCE = db
                db
            }
        }
    }
}
