package com.example.adblocker.util

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局未捕获异常记录器。
 *
 * 背景：
 *   本应用的多数崩溃发生在系统回调中（前台服务、广播接收器、来电筛查），
 *   现场没有 logcat 也没有 adb，用户只能描述「点一下就没反应/闪退了」。
 *   这里把最近的崩溃堆栈持久化到应用私有目录，主界面会展示并提供「复制」，
 *   用户复现一次即可把确定性的堆栈反馈出来。
 *
 * 说明：只记录，不吞异常 —— 记录完成后仍交回系统原本的处理器，
 *      保证崩溃行为（进程退出、ANR 等）与不安装时一致。
 */
object CrashHandler {

    private const val FILE_NAME = "crash_log.txt"

    /** 最多保留的日志字符数，避免文件无限增长。 */
    private const val MAX_CHARS = 20000

    @Volatile
    private var installed = false

    /** 在 Application.onCreate 中调用一次即可。 */
    fun install(context: Context) {
        if (installed) return
        installed = true

        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                append(appContext, thread, throwable)
            } catch (_: Throwable) {
                // 记录失败绝不能影响原有崩溃流程
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** 读取最近的崩溃日志；没有则返回 null。 */
    fun read(context: Context): String? = try {
        val f = File(context.filesDir, FILE_NAME)
        if (!f.exists()) null else f.readText().trim().ifEmpty { null }
    } catch (_: Exception) {
        null
    }

    fun clear(context: Context) {
        try {
            File(context.filesDir, FILE_NAME).delete()
        } catch (_: Exception) {
        }
    }

    /**
     * 主动记录一条异常（供已知敏感路径在捕获后调用）。
     *
     * 例：VPN 隧道在子线程运行，原先的未捕获异常会触发全局处理器并「整个 App 闪退」。
     *     改为在敏感路径捕获后调用本方法，主界面的「崩溃日志」卡片仍能呈现根因，
     *     但不再闪退，便于用户复制反馈。
     */
    fun log(context: Context, throwable: Throwable) {
        try {
            append(context.applicationContext, Thread.currentThread(), throwable)
        } catch (_: Throwable) {
        }
    }

    private fun append(context: Context, thread: Thread, throwable: Throwable) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))

        val entry = buildString {
            append("===== ").append(time)
            append("  pid=").append(android.os.Process.myPid())
            append("  thread=").append(thread.name)
            append(" =====\n")
            append(sw.toString())
            append('\n')
        }

        val f = File(context.filesDir, FILE_NAME)
        val existing = if (f.exists()) f.readText() else ""
        // 新的崩溃放在最前，便于直接看到；超出上限则截断旧内容
        f.writeText((entry + existing).take(MAX_CHARS))
    }
}
