package com.example.adblocker.util

import android.content.Context
import com.example.adblocker.data.BlockListDatabase
import com.example.adblocker.data.BlockType
import com.example.adblocker.data.BlockedEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 拦截记录（电话 / 短信）读写。
 *
 * 为什么这里用「同步 DAO」而不是协程：
 *  调用方是 CallScreeningService 与 BroadcastReceiver，它们都没有协程作用域。
 *  若用 launch 丢到后台写库，接收器可能已经返回、进程随时可能被系统回收，
 *  记录会莫名其妙丢失；而单行插入在毫秒级，数据库已开启 allowMainThreadQueries，
 *  不会造成 ANR。所以这里刻意选择同步执行，并且所有异常都吞掉 ——
 *  记录失败绝不能反过来影响拦截本身。
 */
object BlockLog {

    /** 保留的最大记录条数，超出的自动清理最旧的。 */
    private const val MAX_ROWS = 300

    /** 界面上一次展示多少条。 */
    const val PAGE_SIZE = 50

    /** 相同号码 / 内容在该时间窗内重复命中时合并计数，避免列表被刷屏。 */
    private const val MERGE_WINDOW_MS = 60_000L

    /** 拦截原因代码（存库，不存中文文案）。 */
    const val REASON_BLACKLIST = "blacklist"
    const val REASON_KEYWORD = "keyword"

    /** 短信正文入库前的截断长度，避免一条超长营销短信撑爆记录表。 */
    private const val MAX_CONTENT = 300

    @Volatile
    private var db: BlockListDatabase? = null

    fun init(context: Context) {
        if (db == null) {
            db = BlockListDatabase.getInstance(context.applicationContext)
        }
    }

    // ---------- 写入 ----------

    fun logCall(context: Context, number: String, reason: String) {
        log(context, BlockType.CALL, number, "", reason)
    }

    fun logSms(context: Context, number: String, body: String, reason: String) {
        log(context, BlockType.SMS, number, body, reason)
    }

    private fun log(context: Context, type: Int, number: String, content: String, reason: String) {
        try {
            init(context)
            val dao = db?.blockedEventDao() ?: return
            val now = System.currentTimeMillis()
            val text = if (content.length > MAX_CONTENT) content.take(MAX_CONTENT) + "…" else content

            val latest = dao.getLatest()
            if (latest != null &&
                latest.type == type &&
                latest.number == number &&
                latest.content == text &&
                now - latest.time < MERGE_WINDOW_MS
            ) {
                // 骚扰短信连发、机器人反复来电都很常见，合并成一条并累加次数
                dao.update(latest.copy(time = now, count = latest.count + 1, reason = reason))
            } else {
                dao.insert(
                    BlockedEvent(
                        type = type,
                        number = number,
                        content = text,
                        reason = reason,
                        time = now
                    )
                )
                dao.trim(MAX_ROWS)
            }
        } catch (_: Exception) {
            // 记录失败不影响拦截主流程
        }
    }

    // ---------- 读取 ----------

    /** type 传 null 表示不过滤（全部）。 */
    fun recent(context: Context, type: Int?, limit: Int = PAGE_SIZE): List<BlockedEvent> {
        return try {
            init(context)
            val dao = db?.blockedEventDao() ?: return emptyList()
            if (type == null) dao.getRecent(limit) else dao.getRecentByType(type, limit)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun count(context: Context, type: Int? = null): Int {
        return try {
            init(context)
            val dao = db?.blockedEventDao() ?: return 0
            if (type == null) dao.count() else dao.countByType(type)
        } catch (_: Exception) {
            0
        }
    }

    fun clear(context: Context) {
        try {
            init(context)
            db?.blockedEventDao()?.clear()
        } catch (_: Exception) {
        }
    }

    // ---------- 展示辅助 ----------

    /** 把时间戳格式化成「今天 15:04」/「09-11 15:04」这种一眼能看懂的样式。 */
    fun timeText(time: Long): String {
        val now = System.currentTimeMillis()
        val pattern = if (isSameDay(now, time)) "HH:mm" else "MM-dd HH:mm"
        return try {
            SimpleDateFormat(pattern, Locale.getDefault()).format(Date(time))
        } catch (_: Exception) {
            ""
        }
    }

    private fun isSameDay(a: Long, b: Long): Boolean {
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        return fmt.format(Date(a)) == fmt.format(Date(b))
    }
}
