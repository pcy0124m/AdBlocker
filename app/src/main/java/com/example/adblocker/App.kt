package com.example.adblocker

import android.app.Application
import com.example.adblocker.util.CrashHandler

/**
 * 应用入口：安装全局崩溃记录器。
 *
 * 为什么需要它：
 *   本应用的崩溃大多发生在系统回调里（VPN 服务、短信广播、来电筛查、
 *   开机广播），这些场景用户既看不到日志、也没有 adb，导致「闪退」几乎无法定位。
 *   CrashHandler 会把堆栈落到应用私有目录，主界面顶部会显示，可一键复制反馈。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
    }
}
