package com.example.adblocker.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.adblocker.ComposeSmsActivity
import com.example.adblocker.R

/**
 * 放行短信的通知。
 *
 * 为什么需要它：
 *   一旦本应用成为**默认短信 App**，系统只会把 SMS_DELIVER 发给我们，
 *   用户原先的短信 App 便不再收到新短信、也就不会弹通知。
 *   如果我们不放行通知，用户会以为"短信收不到了"。所以放行的短信由我们自己通知。
 *
 * 点击通知 → 打开 [ComposeSmsActivity] 并预填号码，方便直接回复。
 */
object SmsNotifier {

    private const val CHANNEL_ID = "sms_incoming"
    private const val CHANNEL_NAME = "短信通知"
    private const val CHANNEL_DESC = "收到放行短信时的提醒"

    fun notifyIncoming(context: Context, number: String, body: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = CHANNEL_DESC }
            manager.createNotificationChannel(channel)
        }

        val intent = Intent(context, ComposeSmsActivity::class.java).apply {
            data = Uri.parse("smsto:$number")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            number.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sms)
            .setContentTitle(number)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(number.hashCode(), notification)
        } catch (_: SecurityException) {
            // 未授予 POST_NOTIFICATIONS（Android 13+）时忽略，不影响短信落库
        }
    }
}
