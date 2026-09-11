package com.example.adblocker.util

import android.content.Context

/**
 * 轻量本地偏好存储（SharedPreferences）。
 * 目前只存「开机自启 VPN」开关，供 BootReceiver 在开机广播里读取。
 */
object Prefs {

    private const val FILE = "adblocker_prefs"
    private const val KEY_AUTO_START_VPN = "auto_start_vpn"

    private fun sp(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isAutoStartVpn(context: Context): Boolean =
        sp(context).getBoolean(KEY_AUTO_START_VPN, false)

    fun setAutoStartVpn(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_AUTO_START_VPN, enabled).apply()
    }
}
