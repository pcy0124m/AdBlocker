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

/** 拦截记录的两种类型（存进数据库的是这里的常量值，不要随意改动）。 */
object BlockType {
    const val CALL = 0
    const val SMS = 1
}

/**
 * 一条拦截记录。
 *
 * 注意 reason 存的是**原因代码**（见 BlockLog.REASON_*）而不是中文文案，
 * 这样以后改文案/加多语言时，历史记录也能跟着变。
 */
@Entity(tableName = "blocked_events")
data class BlockedEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "type") val type: Int,
    @ColumnInfo(name = "number") val number: String,
    @ColumnInfo(name = "content") val content: String = "",
    @ColumnInfo(name = "reason") val reason: String = "",
    @ColumnInfo(name = "time") val time: Long,
    @ColumnInfo(name = "count") val count: Int = 1
)

@Dao
interface BlockedEventDao {
    @Query("SELECT * FROM blocked_events ORDER BY time DESC LIMIT :limit")
    fun getRecent(limit: Int): List<BlockedEvent>

    @Query("SELECT * FROM blocked_events WHERE type = :type ORDER BY time DESC LIMIT :limit")
    fun getRecentByType(type: Int, limit: Int): List<BlockedEvent>

    @Query("SELECT * FROM blocked_events ORDER BY time DESC LIMIT 1")
    fun getLatest(): BlockedEvent?

    @Insert
    fun insert(e: BlockedEvent): Long

    @Update
    fun update(e: BlockedEvent)

    @Query("SELECT COUNT(*) FROM blocked_events")
    fun count(): Int

    @Query("SELECT COUNT(*) FROM blocked_events WHERE type = :type")
    fun countByType(type: Int): Int

    @Query("DELETE FROM blocked_events")
    fun clear()

    /** 只保留最新的 limit 条，防止记录表无限膨胀。 */
    @Query(
        "DELETE FROM blocked_events WHERE id NOT IN " +
            "(SELECT id FROM blocked_events ORDER BY time DESC LIMIT :limit)"
    )
    fun trim(limit: Int)
}

@Database(
    entities = [BlockedNumber::class, BlockedEvent::class],
    version = 2,
    exportSchema = false
)
abstract class BlockListDatabase : RoomDatabase() {
    abstract fun blockedNumberDao(): BlockedNumberDao

    abstract fun blockedEventDao(): BlockedEventDao

    companion object {
        @Volatile
        private var INSTANCE: BlockListDatabase? = null

        /**
         * v1 → v2：新增拦截记录表。
         * 用迁移而不是 destructive 重建，避免用户已添加的黑名单被清空。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `blocked_events` (" +
                        "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "`type` INTEGER NOT NULL, " +
                        "`number` TEXT NOT NULL, " +
                        "`content` TEXT NOT NULL, " +
                        "`reason` TEXT NOT NULL, " +
                        "`time` INTEGER NOT NULL, " +
                        "`count` INTEGER NOT NULL)"
                )
            }
        }

        fun getInstance(context: Context): BlockListDatabase {
            return INSTANCE ?: synchronized(this) {
                val db = Room.databaseBuilder(
                    context.applicationContext,
                    BlockListDatabase::class.java,
                    "blocklist.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .allowMainThreadQueries() // 本地小库，便于服务内同步读取缓存
                    .build()
                INSTANCE = db
                db
            }
        }
    }
}
