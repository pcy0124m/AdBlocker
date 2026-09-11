package com.example.adblocker

import android.content.Context
import com.example.adblocker.data.BlockListDatabase
import com.example.adblocker.data.BlockedNumber
import java.util.concurrent.ConcurrentHashMap

/**
 * 黑名单单例：内存缓存（供 CallScreeningService / SmsReceiver 快速查询）+ Room 持久化。
 * 电话与短信共用同一份黑名单。
 */
object BlockListManager {
    @Volatile
    private var db: BlockListDatabase? = null
    private val cache = ConcurrentHashMap.newKeySet<String>()

    fun init(context: Context) {
        if (db == null) {
            db = BlockListDatabase.getInstance(context.applicationContext)
            reload()
        }
    }

    fun reload() {
        cache.clear()
        db?.blockedNumberDao()?.getAll()?.forEach { cache.add(it.number) }
    }

    suspend fun add(raw: String) {
        val n = normalize(raw)
        if (n.isEmpty()) return
        db?.blockedNumberDao()?.insert(BlockedNumber(number = n))
        cache.add(n)
    }

    suspend fun remove(raw: String) {
        val n = normalize(raw)
        db?.blockedNumberDao()?.findByNumber(n)?.let {
            db?.blockedNumberDao()?.delete(it)
        }
        cache.remove(n)
    }

    fun isBlocked(raw: String): Boolean {
        return cache.contains(normalize(raw))
    }

    fun getAll(): List<String> = cache.toList().sorted()

    private fun normalize(n: String): String {
        return n.replace("[^0-9+]".toRegex(), "")
    }
}
