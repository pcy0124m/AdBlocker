package com.example.adblocker

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.telephony.SmsManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

/**
 * 「发送短信」入口，处理 ACTION_SENDTO（scheme: sms / smsto / mms / mmsto）。
 *
 * 为什么需要它：
 *   系统要求**默认短信 App 必须声明一个能处理 ACTION_SENDTO 的 Activity**，
 *   否则本应用根本不会出现在「设置 → 默认应用 → 短信」的可选列表里，
 *   用户点按钮申请 ROLE_SMS 也会失败（这正是"短信开不了"的根因之一）。
 *
 * 这里只提供最简但可用的发送能力，避免本应用被设为默认短信 App 后
 * 变成"发不出短信"的黑洞（例如点击网页上的号码、或其它 App 分享到短信）。
 *
 * 落库说明：
 *   - 普通第三方 App（非默认）用 SmsManager 发出短信后，系统会自动写入短信库；
 *   - **默认短信 App 自己发出的短信系统不会自动落库**，必须自行写入 Telephony.Sms.Sent，
 *     否则用户在自己的短信界面里看不到已发送记录。
 */
class ComposeSmsActivity : AppCompatActivity() {

    private lateinit var etNumber: TextInputEditText
    private lateinit var etBody: TextInputEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_compose_sms)

        etNumber = findViewById(R.id.etSmsNumber)
        etBody = findViewById(R.id.etSmsBody)
        val btnSend = findViewById<MaterialButton>(R.id.btnSendSms)

        // 从 sms:/smsto:13800138000 这类 URI 里取出号码，从 sms_body extra 取内容
        parseIncomingIntent(intent)

        btnSend.setOnClickListener { send() }
    }

    /** 其它 App 通过隐式 intent 传进来的号码 / 正文，预填到输入框。 */
    private fun parseIncomingIntent(intent: android.content.Intent?) {
        // 外部 Intent 的数据不可信（广告里的 sms: URI 格式千奇百怪），
        // 解析失败不能让整个发送界面崩溃。
        try {
            parseIncomingIntentInner(intent)
        } catch (_: Exception) {
        }
    }

    private fun parseIncomingIntentInner(intent: android.content.Intent?) {
        val data: Uri? = intent?.data
        if (data != null) {
            // smsto:10086  /  sms:10086?body=xxx
            val schemeSpecific = data.schemeSpecificPart ?: ""
            val number = schemeSpecific.substringBefore('?').trim()
            if (number.isNotEmpty()) etNumber.setText(number)

            val body = data.getQueryParameter("body")
            if (!body.isNullOrEmpty()) etBody.setText(body)
        }
        // 部分 App 用 extra 传
        val extraNumber = intent?.getStringExtra("address")
            ?: intent?.getStringExtra("sms_address")
        if (!extraNumber.isNullOrEmpty() && etNumber.text.isNullOrEmpty()) {
            etNumber.setText(extraNumber)
        }
        val extraBody = intent?.getStringExtra("sms_body")
            ?: intent?.getStringExtra(android.content.Intent.EXTRA_TEXT)
        if (!extraBody.isNullOrEmpty() && etBody.text.isNullOrEmpty()) {
            etBody.setText(extraBody)
        }
    }

    private fun send() {
        val number = etNumber.text.toString().trim()
        val text = etBody.text.toString()

        if (number.isEmpty()) {
            toast(getString(R.string.compose_empty_number))
            return
        }
        if (text.isEmpty()) {
            toast(getString(R.string.compose_empty_body))
            return
        }
        if (!hasSendPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.SEND_SMS),
                REQ_SEND_SMS
            )
            return
        }
        doSend(number, text)
    }

    private fun doSend(number: String, text: String) {
        try {
            @Suppress("DEPRECATION")
            val sm = SmsManager.getDefault()
            val parts = sm.divideMessage(text)
            if (parts.size > 1) {
                sm.sendMultipartTextMessage(number, null, parts, null, null)
            } else {
                sm.sendTextMessage(number, null, text, null, null)
            }
            persistToSent(number, text)
            toast(getString(R.string.compose_sent))
            finish()
        } catch (e: Exception) {
            toast(getString(R.string.compose_send_failed))
        }
    }

    /**
     * 作为默认短信 App，已发送的短信需要自己写入短信库，否则用户看不到发送记录。
     * 非默认 App 时写入会失败（权限不足），静默忽略即可。
     */
    private fun persistToSent(number: String, text: String) {
        try {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, number)
                put(Telephony.Sms.BODY, text)
                put(Telephony.Sms.DATE, System.currentTimeMillis())
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
            }
            contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
        } catch (_: Exception) {
        }
    }

    private fun hasSendPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_SEND_SMS) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                send()
            } else {
                toast(getString(R.string.compose_no_permission))
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQ_SEND_SMS = 100
    }
}
