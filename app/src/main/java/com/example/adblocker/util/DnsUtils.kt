package com.example.adblocker.util

/**
 * DNS 报文工具：从 DNS 查询包中提取域名、构造 NXDOMAIN 响应、计算 IPv4 首部校验和。
 * 仅处理标准 UDP/DNS（opcode=0、单 question、无压缩指针的常见情况）。
 */

/** 从 DNS 报文中提取查询的域名（小写），失败返回 null。 */
fun extractQueryName(dns: ByteArray): String? {
    if (dns.size < 12) return null
    val qdCount = ((dns[4].toInt() and 0xFF) shl 8) or (dns[5].toInt() and 0xFF)
    if (qdCount < 1) return null

    val sb = StringBuilder()
    var pos = 12
    while (pos < dns.size) {
        val len = dns[pos].toInt() and 0xFF
        if (len == 0) break
        if (len >= 0xC0) { // 压缩指针，简单跳过
            pos += 2
            break
        }
        if (pos + len + 1 > dns.size) break
        if (sb.isNotEmpty()) sb.append('.')
        for (i in 1..len) {
            sb.append(dns[pos + i].toChar())
        }
        pos += len + 1
    }
    return if (sb.isEmpty()) null else sb.toString().lowercase()
}

/** 基于原始查询构造 NXDOMAIN 响应（QR=1, RCODE=3），保留原事务 ID 与问题段。 */
fun buildNxdomain(query: ByteArray): ByteArray {
    val out = query.copyOf()
    // byte2: 置 QR=1（响应），保留 Opcode/AA/TC/RD
    val b2 = query[2].toInt() and 0xFF
    out[2] = (b2 or 0x80).toByte()
    // byte3: RA=1, Z=0, RCODE=3 (NXDOMAIN) -> 1000 0011 = 0x83
    out[3] = 0x83.toByte()
    // 无应答 / 授权 / 额外记录
    out[6] = 0; out[7] = 0
    out[8] = 0; out[9] = 0
    out[10] = 0; out[11] = 0
    return out
}

/** 计算 IPv4 首部校验和（16 位反码求和取反）。len 应为首部长度（通常 20）。 */
fun calcChecksum(buf: ByteArray, offset: Int, len: Int): Short {
    var sum = 0
    var i = offset
    while (i + 1 < offset + len) {
        val word = ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
        sum += word
        i += 2
    }
    while (sum shr 16 != 0) {
        sum = (sum and 0xFFFF) + (sum shr 16)
    }
    return sum.inv().toShort()
}
