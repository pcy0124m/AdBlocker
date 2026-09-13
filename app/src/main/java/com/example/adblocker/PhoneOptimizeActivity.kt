package com.example.adblocker

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.adblocker.util.StorageHelper
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 优化清理页：运行内存展示 + 一键加速 + 存储清理 + 操作日志。
 *
 * 诚实原则：
 * - 运行内存数据来自 ActivityManager.MemoryInfo（真实系统数据）。
 * - 「一键加速」只做两件真事：清理本应用缓存 + 跳转系统释放空间；不杀后台进程。
 *   日志如实记录"受系统限制，结束 0 个后台进程"。
 * - 普通应用无权直接清其它 App 缓存（CLEAR_APP_CACHE 是系统级权限），
 *   所以「扫描垃圾」展示各 App 缓存排行，「去系统清理」跳系统入口。
 */
class PhoneOptimizeActivity : AppCompatActivity() {

    // 运行内存
    private lateinit var tvMemTotal: TextView
    private lateinit var tvMemAvail: TextView
    private lateinit var tvMemPercent: TextView
    private lateinit var progressMemory: ProgressBar

    // 存储
    private lateinit var tvSelfCache: TextView

    // 日志
    private lateinit var tvLog: TextView

    // 日志条目（内存中，不持久化——每次进页面重新开始）
    private val logLines = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_optimize)

        tvMemTotal = findViewById(R.id.tvMemTotal)
        tvMemAvail = findViewById(R.id.tvMemAvail)
        tvMemPercent = findViewById(R.id.tvMemPercent)
        progressMemory = findViewById(R.id.progressMemory)
        tvSelfCache = findViewById(R.id.tvSelfCache)
        tvLog = findViewById(R.id.tvLog)

        // 一键加速：清自身缓存 + 跳系统清理 + 记录日志
        findViewById<MaterialButton>(R.id.btnBoost).setOnClickListener { doBoost() }

        // 扫描垃圾 → 展示各 App 缓存排行（Toast 简报）
        findViewById<MaterialButton>(R.id.btnScanTrash).setOnClickListener { doScanTrash() }

        // 清理缓存 → 清本应用自身缓存
        findViewById<MaterialButton>(R.id.btnClearCache).setOnClickListener { doClearSelfCache() }

        // 清理选中 → 同「清理缓存」（当前选中即本应用）
        findViewById<MaterialButton>(R.id.btnClearSelected).setOnClickListener { doClearSelected() }

        // 应用设置 → 跳系统「释放空间」
        findViewById<MaterialButton>(R.id.btnAppSettings).setOnClickListener {
            startActivity(StorageHelper.phoneCleanerIntent(this))
        }

        refreshMemory()
        refreshStorage()
    }

    // ======================== 运行内存 ========================

    private fun refreshMemory() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val total = memInfo.totalMem          // 字节
        val avail = memInfo.availMem         // 字节
        val used = total - avail
        val percent = if (total > 0) (used * 100 / total).coerceIn(0, 100) else 0

        tvMemTotal.text = formatSize(total)
        tvMemAvail.text = formatSize(avail)
        tvMemPercent.text = "$percent%"
        progressMemory.progress = percent
    }

    // ======================== 一键加速 ========================

    private fun doBoost() {
        // 1. 尝试结束后台进程（免 Root 应用实际无法结束别人的，诚实记录）
        val procCount = StorageHelper.runningProcessCount(this)
        val killed = 0 // 免 Root 无法杀别人进程

        // 2. 清理本应用缓存
        val freed = StorageHelper.clearSelfCache(this)

        log(
            getString(R.string.log_boost_result, killed, procCount, formatSize(freed))
        )

        refreshMemory()
        refreshStorage()

        Toast.makeText(
            this,
            getString(R.string.toast_boost_done, formatSize(freed)),
            Toast.LENGTH_SHORT
        ).show()
    }

    // ======================== 存储清理 ========================

    private fun refreshStorage() {
        tvSelfCache.text = formatSize(StorageHelper.selfCacheSize(this))
    }

    /** 扫描垃圾：统计全机可清理缓存总量并 Toast 展示 */
    private fun doScanTrash() {
        val totalBytes = StorageHelper.totalCacheBytes(this)
        val count = StorageHelper.appCacheList(this).size
        log(getString(R.string.log_scan_result, count, formatSize(totalBytes)))
        Toast.makeText(
            this,
            getString(R.string.toast_scan_result, count, formatSize(totalBytes)),
            Toast.LENGTH_SHORT
        ).show()
    }

    /** 清理本应用缓存 */
    private fun doClearSelfCache() {
        val freed = StorageHelper.clearSelfCache(this)
        log(getString(R.string.log_clear_self_result, formatSize(freed)))
        refreshStorage()
        Toast.makeText(
            this,
            getString(R.string.opt_self_cleared, formatSize(freed)),
            Toast.LENGTH_SHORT
        ).show()
    }

    /** 清理选中（当前=本应用）*/
    private fun doClearSelected() {
        doClearSelfCache()
    }

    // ======================== 日志 ========================

    private fun log(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$time] $msg"
        logLines.add(0, line)           // 最新的在前面
        if (logLines.size > MAX_LOG_LINES) logLines.removeAt(logLines.lastIndex)
        tvLog.text = logLines.joinToString("\n")
    }

    // ======================== 工具 ========================

    private fun formatSize(bytes: Long): String = Formatter.formatFileSize(this, bytes)

    companion object {
        private const val MAX_LOG_LINES = 50
    }
}
