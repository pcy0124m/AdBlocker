package com.example.adblocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.example.adblocker.util.HostsUpdater
import com.example.adblocker.util.Prefs
import com.example.adblocker.util.buildNxdomain
import com.example.adblocker.util.buildServFail
import com.example.adblocker.util.calcChecksum
import com.example.adblocker.util.extractQueryName
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * 本地 DNS 过滤 VPN（无需 Root）。
 *
 * 思路：
 *  - 把虚拟 DNS 设为 10.0.0.1，并只把发往 10.0.0.1/32 的流量引入隧道，
 *    其余流量（含视频流）仍走真实网络，因此不影响带宽。
 *  - 读取隧道里的 IPv4/UDP/DNS 包，取出查询域名；命中广告清单则回 NXDOMAIN，
 *    否则转发到真实上游 DNS 并把应答写回隧道。
 *  - 广告域名来自 HostsUpdater（内置清单 + 在线订阅源热更新，运行中实时生效）。
 *
 * 健壮性设计（重要）：
 *  - 上游 DNS 使用国内可达的公共服务，并有多个备选依次回退；
 *    切勿只写 8.8.8.8 —— 该地址在部分网络下不可达，会导致全部域名解析失败。
 *  - 每次转发都使用带 soTimeout 的独立 socket：既避免并发串包，
 *    也避免上游无响应时线程被 receive() 永久阻塞（会造成全网 DNS 瘫痪）。
 *  - 转发在固定线程池中并发执行，慢查询不会阻塞主循环。
 *  - 全部上游均不可达时返回 SERVFAIL，让客户端快速重试而非干等。
 *
 * 逃生通道：
 *  - 界面上的「暂停拦截」开关：VPN 仍运行，但所有 DNS 直接转发、不过滤。
 *  - 白名单：被误拦截的域名（如某些视频 CDN）可加入白名单立即放行。
 *
 * 已知限制：
 *  - 仅处理 IPv4 + UDP/53 的传统 DNS；DoH/DoT（加密 DNS）无法被此方法拦截。
 */
class AdBlockVpnService : VpnService() {

    private var worker: Thread? = null
    private var fd: ParcelFileDescriptor? = null

    companion object {
        const val NOTIF_ID = 1

        /**
         * 上游 DNS 列表，按优先级排列。
         * 223.5.5.5   阿里公共 DNS
         * 119.29.29.29 腾讯 DNSPod
         * 114.114.114.114 114DNS
         * 8.8.8.8     仅作最后兜底（部分网络不可达）
         */
        val UPSTREAM_DNS = listOf(
            "223.5.5.5",
            "119.29.29.29",
            "114.114.114.114",
            "8.8.8.8"
        )

        /** 单个上游的等待上限；超时即换下一个上游。 */
        private const val DNS_TIMEOUT_MS = 2500

        /** 并发处理 DNS 查询的线程数。 */
        private const val WORKER_THREADS = 8

        @Volatile
        var running = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        HostsUpdater.loadLocal(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
        running = true
        worker = Thread(::vpnLoop, "AdBlockVpnLoop")
        worker?.start()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        try {
            fd?.close()
        } catch (_: Exception) {
        }
        worker?.interrupt()
        // 把本会话拦截次数结算进累计值，供界面统计
        try {
            HostsUpdater.flushSession(this)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val chId = "adblocker_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                chId,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.channel_desc) }
            mgr.createNotificationChannel(ch)
        }
        return NotificationCompat.Builder(this, chId)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_launcher)
            .build()
    }

    private fun vpnLoop() {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .addAddress("10.0.0.2", 24)
            .addDnsServer("10.0.0.1")
            .addRoute("10.0.0.1", 32)
            .addDisallowedApplication(packageName) // 自身流量不走隧道，便于直连上游 DNS

        try {
            fd = builder.establish() ?: return
        } catch (_: Exception) {
            return
        }

        val input = FileInputStream(fd!!.fileDescriptor)
        val out = FileOutputStream(fd!!.fileDescriptor)
        val pool = Executors.newFixedThreadPool(WORKER_THREADS)
        val readBuf = ByteArray(32767)

        try {
            while (running) {
                val len = input.read(readBuf)
                if (len < 0) break
                if (len == 0) continue
                // 拷贝出恰好长度的包，交给线程池并发处理（主循环立即继续读下一包）
                val packet = readBuf.copyOf(len)
                try {
                    pool.execute { handlePacket(packet, out) }
                } catch (_: Exception) {
                    // 线程池已关闭，退出循环
                    break
                }
            }
        } catch (_: Exception) {
            // 隧道关闭或读取被中断时退出
        } finally {
            pool.shutdownNow()
            try { input.close() } catch (_: Exception) {}
            try { out.close() } catch (_: Exception) {}
        }
    }

    /** 解析单个隧道内数据包：仅处理发往 10.0.0.1:53 的 IPv4/UDP/DNS 查询。 */
    private fun handlePacket(packet: ByteArray, out: FileOutputStream) {
        val arr = packet
        if (arr.size < 28) return
        // IPv4?
        if ((arr[0].toInt() and 0xF0) != 0x40) return
        val ipHdrLen = (arr[0].toInt() and 0x0F) * 4
        if (ipHdrLen < 20 || ipHdrLen + 8 > arr.size) return
        // UDP?
        if ((arr[9].toInt() and 0xFF) != 17) return

        val dstPort = ((arr[ipHdrLen + 2].toInt() and 0xFF) shl 8) or
            (arr[ipHdrLen + 3].toInt() and 0xFF)
        if (dstPort != 53) return
        val srcPort = ((arr[ipHdrLen].toInt() and 0xFF) shl 8) or
            (arr[ipHdrLen + 1].toInt() and 0xFF)

        val udpLen = ((arr[ipHdrLen + 4].toInt() and 0xFF) shl 8) or
            (arr[ipHdrLen + 5].toInt() and 0xFF)
        if (udpLen <= 8) return
        val dnsStart = ipHdrLen + 8
        val dnsLen = udpLen - 8
        if (dnsStart + dnsLen > arr.size) return

        val dns = arr.copyOfRange(dnsStart, dnsStart + dnsLen)
        val domain = extractQueryName(dns) ?: return

        val paused = Prefs.isBlockingPaused(this)
        val payload: ByteArray = if (!paused && HostsUpdater.isBlocked(domain)) {
            HostsUpdater.recordBlocked(domain)
            buildNxdomain(dns)
        } else {
            forward(dns) ?: buildServFail(dns)
        }

        writeResponse(out, arr, ipHdrLen, srcPort, dstPort, payload)
    }

    /**
     * 依次尝试各上游 DNS，返回第一个成功应答；全部失败返回 null。
     *
     * 每个上游使用独立 socket（源端口随机），并用 soTimeout 限制等待时间：
     * 既避免多个并发查询在同一 socket 上互相抢包，也避免上游丢包时永久阻塞。
     */
    private fun forward(dns: ByteArray): ByteArray? {
        for (host in UPSTREAM_DNS) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                socket.soTimeout = DNS_TIMEOUT_MS
                socket.connect(InetSocketAddress(InetAddress.getByName(host), 53))
                socket.send(DatagramPacket(dns, dns.size))
                val r = DatagramPacket(ByteArray(4096), 4096)
                socket.receive(r)
                if (r.length > 0) return r.data.copyOf(r.length)
            } catch (_: Exception) {
                // 该上游不可达或超时，尝试下一个
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }
        return null
    }

    private fun writeResponse(
        out: FileOutputStream,
        orig: ByteArray,
        ipHdrLen: Int,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ) {
        val srcIp = ByteArray(4)
        val dstIp = ByteArray(4)
        System.arraycopy(orig, 12, srcIp, 0, 4) // 原包源（设备 10.0.0.2）
        System.arraycopy(orig, 16, dstIp, 0, 4) // 原包目的（虚拟 DNS 10.0.0.1）

        val udpLen = 8 + payload.size
        val total = ipHdrLen + udpLen
        val b = java.nio.ByteBuffer.allocate(total)
        // IPv4 首部
        b.put(0x45.toByte())
        b.put(0)
        b.putShort(total.toShort())
        b.putShort(0)
        b.putShort(0x4000.toShort())
        b.put(64)
        b.put(17)
        b.putShort(0)
        b.put(dstIp) // 应答源 = 虚拟 DNS（原包目的）
        b.put(srcIp) // 应答目的 = 设备（原包源）
        val cs = calcChecksum(b.array(), 0, ipHdrLen)
        b.putShort(10, cs)
        // UDP 首部
        b.putShort(dstPort.toShort()) // 源端口 53
        b.putShort(srcPort.toShort()) // 目的端口 = 原包源端口
        b.putShort(udpLen.toShort())
        b.putShort(0)
        // DNS 载荷
        b.put(payload)

        synchronized(out) {
            out.write(b.array())
            out.flush()
        }
    }
}
