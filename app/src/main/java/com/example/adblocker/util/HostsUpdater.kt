package com.example.adblocker.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * 广告域名集合（单例，进程内共享）。
 *
 * - loadLocal() 从 assets/hosts.txt 载入内置清单；
 * - refresh() 在线拉取订阅源（AdAway / StevenBlack）并合并，支持热更新：
 *   VPN 运行中也实时生效，无需重启。
 */
object HostsUpdater {
    private val hosts = ConcurrentHashMap.newKeySet<String>()
    private const val TAG = "HostsUpdater"
    private val subscriptions = listOf(
        "https://adaway.org/hosts.txt",
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
    )

    fun loadLocal(context: Context) {
        HostsLoader.load(context, hosts)
    }

    fun isBlocked(domain: String): Boolean {
        var d = domain
        while (d.isNotEmpty()) {
            if (hosts.contains(d)) return true
            val idx = d.indexOf('.')
            if (idx < 0) break
            d = d.substring(idx + 1)
        }
        return false
    }

    fun size(): Int = hosts.size

    suspend fun refresh() {
        withContext(Dispatchers.IO) {
            for (url in subscriptions) {
                try {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 15000
                    conn.inputStream.bufferedReader().useLines { lines ->
                        for (raw in lines) {
                            val line = raw.trim()
                            if (line.isEmpty() || line.startsWith("#")) continue
                            val parts = line.split(Regex("\\s+"))
                            if (parts.isEmpty()) continue
                            val candidate =
                                if (parts.size >= 2 && looksLikeIp(parts[0])) parts[1] else parts[0]
                            if (candidate.contains('.') &&
                                !candidate.any { !it.isLetter() && it !in ".-" }
                            ) {
                                hosts.add(candidate.lowercase())
                            }
                        }
                    }
                    conn.disconnect()
                } catch (e: Exception) {
                    Log.w(TAG, "fetch failed: $url", e)
                }
            }
        }
    }

    private fun looksLikeIp(s: String): Boolean =
        s.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))
}
