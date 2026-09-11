package com.example.adblocker.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * 广告域名集合（单例，进程内共享）。
 *
 * - loadLocal() 从 assets/hosts.txt 载入内置清单，并载入用户白名单；
 * - refresh() 在线拉取订阅源（AdAway / StevenBlack）并合并，支持热更新：
 *   VPN 运行中也实时生效，无需重启；
 * - 白名单优先级最高：命中白名单的域名及其子域一定放行（用于排除误杀）；
 * - 记录本会话拦截次数与最近拦截域名，供界面统计展示。
 */
object HostsUpdater {
    private val hosts = ConcurrentHashMap.newKeySet<String>()
    private val whitelist = ConcurrentHashMap.newKeySet<String>()
    private val sessionBlocked = AtomicLong(0)
    private val recent = ConcurrentLinkedDeque<String>()

    private const val TAG = "HostsUpdater"
    private const val RECENT_MAX = 50

    private val subscriptions = listOf(
        "https://adaway.org/hosts.txt",
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
    )

    /**
     * 订阅源 / hosts 文件里必然出现、但绝不能当成广告域名的本地保留名。
     * 把它们加进黑名单会导致本机名称解析被误伤。
     */
    private val reserved = setOf(
        "localhost", "localhost.localdomain", "local", "broadcasthost",
        "ip6-localhost", "ip6-loopback", "ip6-localnet", "ip6-mcastprefix",
        "ip6-allnodes", "ip6-allrouters", "ip6-allhosts", "0.0.0.0"
    )

    fun loadLocal(context: Context) {
        HostsLoader.load(context, hosts)
        whitelist.clear()
        whitelist.addAll(Prefs.getWhitelist(context))
    }

    /** 判断域名（含其父域）是否被用户加入白名单。 */
    private fun isWhitelisted(domain: String): Boolean {
        var d = domain
        while (d.isNotEmpty()) {
            if (whitelist.contains(d)) return true
            val idx = d.indexOf('.')
            if (idx < 0) break
            d = d.substring(idx + 1)
        }
        return false
    }

    /** 域名或其任一父域命中黑名单即视为广告；白名单优先。 */
    fun isBlocked(domain: String): Boolean {
        if (domain.isEmpty()) return false
        if (isWhitelisted(domain)) return false
        var d = domain
        while (d.isNotEmpty()) {
            if (hosts.contains(d)) return true
            val idx = d.indexOf('.')
            if (idx < 0) break
            d = d.substring(idx + 1)
        }
        return false
    }

    // ---------- 统计 ----------

    fun recordBlocked(domain: String) {
        sessionBlocked.incrementAndGet()
        recent.addFirst(domain)
        while (recent.size > RECENT_MAX) {
            recent.pollLast()
        }
    }

    fun sessionBlockedCount(): Long = sessionBlocked.get()

    fun recentBlocked(): List<String> = recent.toList()

    fun size(): Int = hosts.size

    /** 把本会话计数结算进累计值（原子取走，避免重复累计）。 */
    fun flushSession(context: Context) {
        val n = sessionBlocked.getAndSet(0)
        if (n > 0) Prefs.addTotalBlocked(context, n)
    }

    // ---------- 白名单 ----------

    fun whiteList(): List<String> = whitelist.sorted()

    /** 添加白名单域名；返回 false 表示格式不合法。支持传入 "*.example.com" 形式。 */
    fun addWhitelist(context: Context, rawDomain: String): Boolean {
        val d = rawDomain.trim().lowercase()
            .removePrefix("*.")
            .removePrefix(".")
            .trim('.')
        if (!isValidDomain(d)) return false
        whitelist.add(d)
        Prefs.setWhitelist(context, whitelist)
        return true
    }

    fun removeWhitelist(context: Context, domain: String) {
        whitelist.remove(domain.trim().lowercase())
        Prefs.setWhitelist(context, whitelist)
    }

    // ---------- 在线更新 ----------

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
                            val d = candidate.lowercase().trim('.')
                            if (isValidDomain(d)) hosts.add(d)
                        }
                    }
                    conn.disconnect()
                } catch (e: Exception) {
                    Log.w(TAG, "fetch failed: $url", e)
                }
            }
        }
    }

    /**
     * 是否是可用于黑名单的域名。
     * 允许字母 / 数字 / 点 / 连字符（CDN 域名常含数字，不能一刀切排除），
     * 但排除本地保留名、纯 IP 段、以及明显非域名的字符串。
     */
    private fun isValidDomain(s: String): Boolean {
        if (s.length < 4 || s.length > 253) return false
        if (s in reserved) return false
        if (!s.contains('.')) return false
        if (s.startsWith('.') || s.endsWith('.') || s.contains("..")) return false
        if (!s.any { it.isLetter() }) return false
        if (!s.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }) return false
        val tld = s.substringAfterLast('.')
        return tld.length >= 2
    }

    private fun looksLikeIp(s: String): Boolean =
        s.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))
}
