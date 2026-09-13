package com.example.adblocker.util

import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * DNS 应答内存缓存：对已经向上游解析过的域名，在短时间内直接返回缓存的应答，
 * 避免对每一条「放行」查询都再发一次真实 UDP 请求。
 *
 * 这直接解决了「开 VPN 刷视频卡顿」的根因 —— 短剧 / 视频 App 一打开会爆发式请求
 * 几十个 CDN 域名，而且反复请求同一批域名；命中缓存后这些重复请求完全不碰网络，
 * 既降低延迟又省电。
 *
 * 关键点：
 *  - 事务 ID 必须重写：上游应答里的事务 ID 来自「上次」客户端查询，若直接回给
 *    另一个 ID 不同的客户端，客户端会因 ID 不匹配而丢弃应答并重新请求，
 *    那样缓存反而帮了倒忙。命中时把应答前 2 字节改成当前查询的事务 ID。
 *  - 只缓存「放行的域名」：广告域名本就在黑名单里直接回 NXDOMAIN，从不进缓存。
 *  - TTL 固定 60s：足够覆盖 App 启动时的域名爆发请求；过长可能因 IP 变更导致失败重试。
 *  - 上限 2000 条，超了清掉最旧的一半，防止列表无限膨胀。
 *  - 暂停 / 恢复拦截、在线更新规则时由调用方主动 [clear]，避免逻辑不一致（如漏拦）。
 */
object DnsCache {

    private const val TTL_MS = 60_000L
    private const val MAX_ENTRIES = 2000

    private data class Entry(val payload: ByteArray, val expireAt: Long)

    private val map =
        Collections.synchronizedMap(LinkedHashMap<String, Entry>(MAX_ENTRIES, 0.75f, true))

    private val hits = AtomicLong(0)

    /** 命中次数（供界面统计「省去重复查询」）。 */
    fun hitCount(): Long = hits.get()

    /** 当前缓存条目数（诊断用）。 */
    fun size(): Int = map.size

    /**
     * 取缓存应答；命中则用当前查询的 ID 重写事务 ID 后返回，未命中 / 过期返回 null。
     * @param domain   小写域名
     * @param queryDns 原始查询的 DNS 包（前 2 字节为事务 ID）
     */
    fun get(domain: String, queryDns: ByteArray): ByteArray? {
        val entry = map[domain] ?: return null
        if (System.currentTimeMillis() > entry.expireAt) {
            map.remove(domain)
            return null
        }
        hits.incrementAndGet()
        val out = entry.payload.copyOf()
        if (out.size >= 2 && queryDns.size >= 2) {
            out[0] = queryDns[0]
            out[1] = queryDns[1]
        }
        return out
    }

    /** 写入一条成功解析的应答（dns 层 payload，前 2 字节已是查询的事务 ID）。 */
    fun put(domain: String, payload: ByteArray) {
        if (payload.size < 12) return
        synchronized(map) {
            if (map.size >= MAX_ENTRIES) {
                // 超上限时清掉最旧的一半，避免逐个遍历开销过大
                val it = map.keys.iterator()
                var drop = map.size / 2
                while (it.hasNext() && drop-- > 0) it.remove()
            }
            map[domain] = Entry(payload, System.currentTimeMillis() + TTL_MS)
        }
    }

    /** 主动清空：暂停 / 恢复拦截、在线更新规则时调用，保证缓存与拦截策略一致。 */
    fun clear() {
        map.clear()
        hits.set(0)
    }
}
