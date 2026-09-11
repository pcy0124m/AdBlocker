package com.example.adblocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.example.adblocker.util.Prefs

/**
 * 开机（或部分机型的快速重启）后自动拉起广告拦截 VPN。
 *
 * 前置条件：
 *  1. 用户在 App 内打开了「开机自动启动广告拦截」开关；
 *  2. VPN 已授权过（VpnService.prepare 返回 null）。
 *
 * 注意：BootReceiver 里无法弹 VPN 授权对话框，因此未授权时静默跳过，
 * 用户手动打开一次 App 启动 VPN 后，后续开机会自动拉起。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != ACTION_QUICKBOOT_POWERON &&
            action != ACTION_HTC_QUICKBOOT_POWERON
        ) {
            return
        }

        if (!Prefs.isAutoStartVpn(context)) return

        // prepare 返回 null 表示已有 VPN 授权，可在后台直接启动；非 null 则需前台弹窗，跳过。
        if (VpnService.prepare(context) != null) return

        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AdBlockVpnService::class.java)
            )
        } catch (e: Exception) {
            // 后台启动前台服务可能被省电策略拦截，忽略即可，用户可手动开启。
        }
    }

    private companion object {
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        const val ACTION_HTC_QUICKBOOT_POWERON = "com.htc.intent.action.QUICKBOOT_POWERON"
    }
}
