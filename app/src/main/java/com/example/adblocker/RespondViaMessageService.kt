package com.example.adblocker

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.telephony.SmsManager

/**
 * 来电时「用消息回复」的后端服务，处理 ACTION_RESPOND_VIA_MESSAGE。
 *
 * 为什么需要它：
 *   这是系统要求的**默认短信 App 资格组件**之一（且必须声明
 *   android:permission="android.permission.SEND_RESPOND_VIA_MESSAGE"）。
 *   缺少它，本应用不会出现在「默认应用 → 短信」列表里。
 *
 * 触发场景：来电时选择"用消息回复"快捷短语，电话 App 会把要发的号码（EXTRA_EMAIL）
 * 和内容（EXTRA_TEXT）发到这里，由本应用负责真正发出。
 */
class RespondViaMessageService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val recipients = intent?.getStringArrayExtra(Intent.EXTRA_EMAIL)
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)

        if (recipients.isNullOrEmpty() || text.isNullOrEmpty()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // 发送是阻塞操作，放到子线程；完成后停止服务
        Thread {
            try {
                @Suppress("DEPRECATION")
                val sm = SmsManager.getDefault()
                for (recipient in recipients) {
                    if (recipient.isNullOrBlank()) continue
                    val parts = sm.divideMessage(text)
                    if (parts.size > 1) {
                        sm.sendMultipartTextMessage(recipient, null, parts, null, null)
                    } else {
                        sm.sendTextMessage(recipient, null, text, null, null)
                    }
                }
            } catch (_: Exception) {
                // 无 SEND_SMS 权限或号码非法时静默忽略，避免 Service 崩溃
            } finally {
                stopSelf(startId)
            }
        }.start()

        return START_NOT_STICKY
    }
}
