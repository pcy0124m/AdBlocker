package com.example.adblocker

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import androidx.annotation.RequiresApi
import com.example.adblocker.util.BlockLog

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
        val number = try {
            details.handle?.schemeSpecificPart
        } catch (_: Exception) {
            null
        }

        val blockedByList = try {
            BlockListManager.init(applicationContext)
            !number.isNullOrEmpty() && BlockListManager.isBlocked(number)
        } catch (_: Exception) {
            false
        }
        // 内置骚扰号段库：境外 / 虚拟运营商转售号段（开箱即用，无需手动加黑名单）
        val blockedByPrefix = try {
            !number.isNullOrEmpty() && SpamRules.isSpamCall(number)
        } catch (_: Exception) {
            false
        }
        val block = blockedByList || blockedByPrefix

        if (block && !number.isNullOrEmpty()) {
            // 记一笔拦截记录，界面上就能看到「拦了谁、什么时候拦的、为什么拦」
            try {
                val reason = if (blockedByList) BlockLog.REASON_BLACKLIST else BlockLog.REASON_PREFIX
                BlockLog.logCall(applicationContext, number, reason)
            } catch (_: Exception) {
                // 记录失败不影响拒接
            }
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
