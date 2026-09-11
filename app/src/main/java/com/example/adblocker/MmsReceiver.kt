package com.example.adblocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 彩信（MMS）到达广播，处理 WAP_PUSH_DELIVER。
 *
 * 为什么需要它：
 *   系统要求默认短信 App 必须声明此接收器（mimeType 为
 *   application/vnd.wap.mms-message，并受 BROADCAST_WAP_PUSH 权限保护）。
 *   缺少它，本应用不会被判定为合格的默认短信 App。
 *
 * 重要限制（请知悉）：
 *   本应用**不做彩信解析与落库**。因此当本应用被设为默认短信 App 后，
 *   收到的彩信将不会被保存，也不会显示（短信不受影响）。
 *   当前国内彩信已基本停用，属于可接受的取舍；如需完整彩信支持，
 *   请改用专门的短信应用。
 *
 * 健壮性：广播接收器崩溃会直接表现为「应用已停止运行」，因此这里只打日志，且全程兜底。
 */
class MmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        try {
            Log.i(TAG, "收到 WAP_PUSH（彩信）广播，本应用不处理彩信，已忽略。")
        } catch (_: Exception) {
            // 任何异常都不应影响系统彩信流程
        }
    }

    private companion object {
        const val TAG = "MmsReceiver"
    }
}
