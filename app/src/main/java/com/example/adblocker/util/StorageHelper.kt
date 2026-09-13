package com.example.adblocker.util

import android.app.ActivityManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.storage.StorageManager
import android.provider.Settings
import android.text.format.Formatter
import java.io.File

/**
 * 手机存储 / 进程体检能力。
 *
 * 诚实边界（重要，避免做成「伪加速」）：
 *  - 普通（非系统、无 Root）应用**没有权限直接清理其它 App 的缓存**，
 *    `CLEAR_APP_CACHE` 是 signature|system 级权限。能真实做的是：
 *    ① 清理「本应用自身」的缓存（立竿见影，安全）；
 *    ② 读取各 App 的缓存占用（StorageStatsManager，需 GET_PACKAGE_SIZE 普通权限），
 *       展示排行榜并引导用户去系统设置手动清理；
 *    ③ 读取当前后台可见进程数做「体检」展示。
 *  - 「强制结束后台进程」是伪科学：Android 会自动回收内存，强杀反而让 App 重启更慢、更耗电。
 *    因此这里**不做**杀进程，只做上述真实有效的部分。
 */
object StorageHelper {

    data class AppCacheInfo(
        val appName: String,
        val packageName: String,
        val cacheBytes: Long,
        val isSystem: Boolean
    )

    /** 清理本应用自身缓存，返回释放的字节数。 */
    fun clearSelfCache(context: Context): Long {
        val dirs = listOfNotNull(
            context.cacheDir,
            context.codeCacheDir,
            context.externalCacheDir
        )
        var freed = 0L
        for (d in dirs) {
            freed += d.lengthRecursive()
            d.deleteRecursivelySafe()
        }
        return freed
    }

    /** 统计自身缓存当前占用（清理前展示用）。 */
    fun selfCacheSize(context: Context): Long =
        listOfNotNull(context.cacheDir, context.codeCacheDir, context.externalCacheDir)
            .sumOf { it.lengthRecursive() }

    /** 各 App 缓存占用，按大小降序；仅返回有缓存的。API 26+ 才支持，更低版本返回空。 */
    fun appCacheList(context: Context): List<AppCacheInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return emptyList()
        val pm = context.packageManager
        val ssm = context.getSystemService(StorageStatsManager::class.java) ?: return emptyList()
        val uuid = StorageManager.UUID_DEFAULT
        val user = Process.myUserHandle()
        val result = mutableListOf<AppCacheInfo>()
        for (app in pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
            try {
                val stats = ssm.queryStatsForPackage(uuid, app.packageName, user)
                val cache = stats.cacheBytes
                if (cache <= 0) continue
                result.add(
                    AppCacheInfo(
                        appName = pm.getApplicationLabel(app).toString(),
                        packageName = app.packageName,
                        cacheBytes = cache,
                        isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    )
                )
            } catch (_: Exception) {
                // 个别包查询失败（跨用户 / 权限瞬间变化），跳过
            }
        }
        return result.sortedByDescending { it.cacheBytes }
    }

    /** 全机各 App 可清理的缓存总量（只读估算，用于提示「手机垃圾约 X」）。 */
    fun totalCacheBytes(context: Context): Long =
        appCacheList(context).sumOf { it.cacheBytes }

    /**
     * 跳转系统「释放空间 / 存储管理」做全机清理。
     * 普通应用无权直接清其它 App 缓存，但系统自带的存储清理可以——这是真正能「清理手机」的入口。
     * 优先 ACTION_MANAGE_STORAGE（存储管理器，可一键清全机缓存），不支持时退回内部存储设置页。
     */
    fun phoneCleanerIntent(context: Context): Intent {
        val primary = Intent(Settings.ACTION_MANAGE_STORAGE)
        if (primary.resolveActivity(context.packageManager) != null) return primary
        return Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
    }

    /** 后台可见进程数（仅当前用户；Android 10+ 受限制，数字仅供参考）。 */
    fun runningProcessCount(context: Context): Int = try {
        val am = context.getSystemService(ActivityManager::class.java)
        am?.runningAppProcesses?.size ?: 0
    } catch (_: Exception) {
        0
    }

    /** 跳转到某 App 的系统设置详情页（便于手动清缓存）。 */
    fun appDetailsIntent(packageName: String): Intent =
        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun formatSize(context: Context, bytes: Long): String =
        Formatter.formatFileSize(context, bytes)

    // ---------- 文件大小 / 删除辅助 ----------

    private fun File.lengthRecursive(): Long {
        if (!exists()) return 0L
        if (isFile) return length()
        var total = 0L
        listFiles()?.forEach { total += it.lengthRecursive() }
        return total
    }

    private fun File.deleteRecursivelySafe() {
        if (!exists()) return
        try {
            deleteRecursively()
        } catch (_: Exception) {
            // 极少量文件可能被占用，忽略即可，不影响整体清理
        }
    }
}
