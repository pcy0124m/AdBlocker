package com.example.adblocker

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import androidx.annotation.RequiresApi

/**
 * 骚扰电话拦截服务（Android 7.0+）。
 *
 * 拦截逻辑：来电号码命中本地黑名单（BlockListManager）则直接拒接。
 * 使用前需通过 RoleManager 申请 ROLE_CALL_SCREENING（在 MainActivity 中处理）。
 */
@RequiresApi(Build.VERSION_CODES.N)
class CallBlockerService : CallScreeningService() {

    override fun onScreenCall(details: Call.Details) {
        BlockListManager.init(applicationContext)

        val number = details.handle?.schemeSpecificPart
        val block = if (number.isNullOrEmpty()) {
            false
        } else {
            BlockListManager.isBlocked(number)
        }
        respond(details, block)
    }

    private fun respond(details: Call.Details, block: Boolean) {
        // 注意：来电响应类是 CallScreeningService 的嵌套类 CallResponse，
        // 不是 android.telecom.Call.Response。
        val resp = CallScreeningService.CallResponse.Builder()
        if (block) {
            resp.setDisallowCall(true)
            resp.setRejectCall(true)
        } else {
            resp.setDisallowCall(false)
        }
        respondToCall(details, resp.build())
    }
}
