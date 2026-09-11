package com.example.adblocker.util

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/** 从 assets/hosts.txt 载入广告域名到给定集合中。支持 "0.0.0.0 domain" 与纯域名两种格式。 */
object HostsLoader {
    fun load(context: Context, set: MutableSet<String>) {
        try {
            context.assets.open("hosts.txt").bufferedReader().useLines { lines ->
                for (raw in lines) {
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) continue
                    val parts = line.split(Regex("\\s+"))
                    val domain = if (parts.size >= 2) parts.last() else parts[0]
                    val d = domain.lowercase()
                    if (d.isNotEmpty()) set.add(d)
                }
            }
        } catch (_: Exception) {
            // assets 缺失时静默跳过，列表为空即不拦截
        }
    }
}
