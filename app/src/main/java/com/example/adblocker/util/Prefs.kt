package com.example.adblocker.util

import android.content.Context

/**
 * 轻量本地偏好存储（SharedPreferences）。
 *
 * - 开机自启 VPN 开关（供 BootReceiver 读取）
 * - 暂停拦截开关（VPN 仍运行，但所有 DNS 直接转发、不过滤）
 * - 广告域名白名单（避免误杀，例如某些短剧 / 视频 App 的 CDN）
 * - 累计拦截次数（用于界面统计）
 */
object Prefs {

    private const val FILE = "adblocker_prefs"
    private const val KEY_AUTO_START_VPN = "auto_start_vpn"
    private const val KEY_PAUSE_BLOCKING = "pause_blocking"
    private const val KEY_WHITELIST = "whitelist"
    private const val KEY_TOTAL_BLOCKED = "total_blocked"

    private fun sp(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---------- 开机自启 ----------
    fun isAutoStartVpn(context: Context): Boolean =
        sp(context).getBoolean(KEY_AUTO_START_VPN, false)

    fun setAutoStartVpn(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_AUTO_START_VPN, enabled).apply()
    }

    // ---------- 暂停拦截 ----------
    /**
     * true 时 VPN 照常运行，但所有 DNS 查询直接转发上游、不做任何过滤。
     * 用途：某个 App（如短剧 / 视频）被误拦截时，一键排除故障。
     */
    fun isBlockingPaused(context: Context): Boolean =
        sp(context).getBoolean(KEY_PAUSE_BLOCKING, false)

    fun setBlockingPaused(context: Context, paused: Boolean) {
        sp(context).edit().putBoolean(KEY_PAUSE_BLOCKING, paused).apply()
    }

    // ---------- 白名单 ----------
    fun getWhitelist(context: Context): Set<String> =
        sp(context).getStringSet(KEY_WHITELIST, emptySet()) ?: emptySet()

    fun setWhitelist(context: Context, list: Collection<String>) {
        // 必须传入新集合：SP 返回的 Set 不可直接改动
        sp(context).edit().putStringSet(KEY_WHITELIST, HashSet(list)).apply()
    }

    // ---------- 累计拦截次数 ----------
    fun totalBlocked(context: Context): Long =
        sp(context).getLong(KEY_TOTAL_BLOCKED, 0L)

    fun addTotalBlocked(context: Context, delta: Long) {
        if (delta <= 0) return
        val prefs = sp(context)
        prefs.edit()
            .putLong(KEY_TOTAL_BLOCKED, prefs.getLong(KEY_TOTAL_BLOCKED, 0L) + delta)
            .apply()
    }
}
