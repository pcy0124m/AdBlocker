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
 *
 * 健壮性：onScreenCall 必须回包，否则系统会一直等到超时（表现为来电界面卡住）；
 *   同时这里也不能抛异常 —— 服务崩溃会连带整个 App 闪退。
 */
@RequiresApi(Build.VERSION_CODES.N)
class CallBlockerService : CallScreeningService() {

    override fun onScreenCall(details: Call.Details) {
        val block = try {
            BlockListManager.init(applicationContext)
            val number = details.handle?.schemeSpecificPart
            if (number.isNullOrEmpty()) false else BlockListManager.isBlocked(number)
        } catch (_: Exception) {
            // 查询失败时按「不拦截」处理，优先保证来电能正常接通
            false
        }

        try {
            respond(details, block)
        } catch (_: Exception) {
            // 回包失败也不崩；系统最终会按默认策略处理该来电
        }
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
