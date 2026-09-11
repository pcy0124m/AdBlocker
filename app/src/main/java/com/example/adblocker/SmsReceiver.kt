package com.example.adblocker

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import com.example.adblocker.util.SmsNotifier

/**
 * 垃圾短信拦截接收器。
 *
 * 两种工作模式：
 *
 * 1) 已设为默认短信 App（推荐，可真正拦截）：
 *    系统改走 SMS_DELIVER；对命中的短信我们【不落库】从而不显示，
 *    对放行的短信需自行写入短信库（此处做最简写入）。
 *
 * 2) 未设为默认短信 App（折中方案，尽量拦）：
 *    Android 4.4+ 起，系统始终会把短信写入默认短信 App，第三方 App 的
 *    abortBroadcast() 无法阻止系统落库，因此无法 100% 拦截。
 *    我们仍做以下「尽量拦」努力：
 *      a. 高优先级有序广播（manifest 中 android:priority=1000）下调用 abortBroadcast()，
 *         可在部分机型/旧版本上拦掉广播，并阻止其它第三方短信类 App 收到；
 *      b. 尽力尝试从短信库删除这条命中消息（best-effort，多数机型会因权限被静默忽略）。
 *    真正稳定地拦截短信，请把本 App 设为默认短信 App（MainActivity 中通过 RoleManager 申请）。
 *
 * 健壮性：整个处理流程都包在 try/catch 内。
 *   广播接收器里抛出的异常会直接表现为「应用已停止运行」（闪退），
 *   而广告/垃圾短信的 PDU 格式往往不规范（class 0、超长、无 UDH 等），
 *   SmsMessage.createFromPdu() 有抛异常的可能，因此必须兜底。
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        try {
            handleSms(context, intent)
        } catch (e: Exception) {
            Log.w(TAG, "处理短信广播失败，已忽略（不中断系统短信流程）", e)
        }
    }

    private fun handleSms(context: Context, intent: Intent) {
        BlockListManager.init(context)
        val action = intent.action ?: return
        val isDeliver = action == Telephony.Sms.Intents.SMS_DELIVER_ACTION
        val isReceived = action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION
        if (!isDeliver && !isReceived) return

        val pdus = intent.extras?.get("pdus") as? Array<*> ?: return
        val format = intent.getStringExtra("format")

        val body = StringBuilder()
        var number: String? = null
        for (pdu in pdus) {
            val bytes = pdu as? ByteArray ?: continue
            val sms = try {
                SmsMessage.createFromPdu(bytes, format)
            } catch (e: Exception) {
                // 个别畸形 PDU（广告短信常见）解析失败时跳过这一条，不影响其余
                Log.w(TAG, "解析短信 PDU 失败，已跳过", e)
                null
            } ?: continue
            if (number == null) number = sms.displayOriginatingAddress
            body.append(sms.messageBody)
        }
        if (number == null) return

        val blocked = BlockListManager.isBlocked(number) || containsSpam(body.toString())

        if (blocked) {
            if (isDeliver) {
                // 默认短信 App：不落库即不显示，实现真正拦截。
                return
            } else {
                // 非默认短信 App：best-effort 尽量拦。
                // a. 有序广播中拦截，阻止其它第三方 App 接收（对系统自身落库无效）。
                try {
                    abortBroadcast()
                } catch (_: Exception) {
                    // 非有序广播或已被系统提前处理后 abort 无效，忽略。
                }
                // b. 尽力删除已落库的命中消息（多数机型会因 WRITE_SMS 权限被忽略）。
                tryDeleteFromInbox(context, number, body.toString())
            }
        } else if (isDeliver) {
            // 默认短信 App 必须负责把放行的短信写入短信库（写入后，用户原来的短信 App 才能读到）。
            persistMessages(context, pdus, format)
            // 默认短信 App 场景下，其它 App 收不到新短信也就不会弹通知，
            // 所以放行的短信需要由我们自己通知，避免用户以为"短信收不到了"。
            SmsNotifier.notifyIncoming(context, number, body.toString())
        }
    }

    /**
     * 非默认短信 App 的 best-effort 删除：仅当本 App 恰好是默认短信 App 时才能写短信库，
     * 否则 delete 会因权限抛异常并被忽略——这是预期行为，不影响主流程。
     */
    private fun tryDeleteFromInbox(context: Context, number: String, body: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            try {
                val where = "${Telephony.Sms.ADDRESS}=? AND ${Telephony.Sms.BODY}=?"
                val selArgs = arrayOf(normalize(number), body)
                context.contentResolver.delete(
                    Telephony.Sms.CONTENT_URI,
                    where,
                    selArgs
                )
            } catch (_: Exception) {
                // 非默认短信 App 写入短信库会失败，best-effort，忽略。
            }
        }
    }

    private fun normalize(n: String): String =
        n.replace(Regex("[^0-9+]"), "")

    private fun persistMessages(context: Context, pdus: Array<*>, format: String?) {
        val values = mutableListOf<ContentValues>()
        for (pdu in pdus) {
            val bytes = pdu as? ByteArray ?: continue
            val sms = try {
                SmsMessage.createFromPdu(bytes, format)
            } catch (_: Exception) {
                continue
            }
            values.add(
                ContentValues().apply {
                    put(Telephony.Sms.ADDRESS, sms.displayOriginatingAddress)
                    put(Telephony.Sms.BODY, sms.messageBody)
                    put(Telephony.Sms.DATE, System.currentTimeMillis())
                    put(Telephony.Sms.READ, 0)
                    put(Telephony.Sms.SEEN, 0)
                }
            )
        }
        if (values.isEmpty()) return
        try {
            context.contentResolver.bulkInsert(
                Telephony.Sms.Inbox.CONTENT_URI,
                values.toTypedArray()
            )
        } catch (_: Exception) {
        }
    }

    private fun containsSpam(body: String): Boolean {
        val keywords = listOf(
            "中奖", "返利", "免费领取", "点击链接", "退订回T",
            "贷款", "赌博", "优惠促销", "限时特惠", "免息"
        )
        return keywords.any { body.contains(it) }
    }

    private companion object {
        const val TAG = "SmsReceiver"
    }
}
