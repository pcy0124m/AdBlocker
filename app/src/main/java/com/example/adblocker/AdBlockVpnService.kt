package com.example.adblocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.example.adblocker.util.HostsUpdater
import com.example.adblocker.util.buildNxdomain
import com.example.adblocker.util.calcChecksum
import com.example.adblocker.util.extractQueryName
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer

/**
 * 本地 DNS 过滤 VPN（无需 Root）。
 *
 * 思路：
 *  - 把虚拟 DNS 设为 10.0.0.1，并只把发往 10.0.0.1/32 的流量引入隧道，
 *    其余流量仍走真实网络，因此不必代理所有数据。
 *  - 读取隧道里的 IPv4/UDP/DNS 包，取出查询域名；命中广告清单则回 NXDOMAIN，
 *    否则转发到真实上游 DNS（8.8.8.8）并把应答写回隧道。
 *  - 广告域名来自 HostsUpdater（内置清单 + 在线订阅源热更新，VPN 运行中实时生效）。
 *
 * 已知限制：
 *  - 仅处理 IPv4 + UDP/53 的传统 DNS；DoH/DoT（加密 DNS）无法被此方法拦截。
 */
class AdBlockVpnService : VpnService() {

    private var worker: Thread? = null
    private var fd: ParcelFileDescriptor? = null

    companion object {
        const val NOTIF_ID = 1
        const val UPSTREAM_DNS = "8.8.8.8"

        @Volatile
        var running = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        HostsUpdater.loadLocal(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
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

        val `in` = FileInputStream(fd!!.fileDescriptor)
        val out = FileOutputStream(fd!!.fileDescriptor)
        val buf = ByteBuffer.allocate(32767)
        val upstream = InetSocketAddress(InetAddress.getByName(UPSTREAM_DNS), 53)
        val socket = DatagramSocket()
        val respBuf = ByteArray(4096)

        try {
            while (running) {
                buf.clear()
                val len = `in`.read(buf.array())
                if (len < 0) break
                if (len == 0) continue
                buf.limit(len)
                processPacket(buf, out, socket, upstream, respBuf)
            }
        } catch (_: Exception) {
            // 隧道关闭或读取被中断时退出
        } finally {
            socket.close()
            try { `in`.close() } catch (_: Exception) {}
            try { out.close() } catch (_: Exception) {}
        }
    }

    private fun processPacket(
        buf: ByteBuffer,
        out: FileOutputStream,
        socket: DatagramSocket,
        upstream: InetSocketAddress,
        respBuf: ByteArray
    ) {
        val arr = buf.array()
        // IPv4?
        if ((arr[0].toInt() and 0xF0) != 0x40) return
        val ipHdrLen = (arr[0].toInt() and 0x0F) * 4
        if (ipHdrLen < 20) return
        // UDP?
        if ((arr[9].toInt() and 0xFF) != 17) return

        val dstPort = ((arr[ipHdrLen + 2].toInt() and 0xFF) shl 8) or (arr[ipHdrLen + 3].toInt() and 0xFF)
        if (dstPort != 53) return
        val srcPort = ((arr[ipHdrLen].toInt() and 0xFF) shl 8) or (arr[ipHdrLen + 1].toInt() and 0xFF)

        val udpLen = ((arr[ipHdrLen + 4].toInt() and 0xFF) shl 8) or (arr[ipHdrLen + 5].toInt() and 0xFF)
        val dnsStart = ipHdrLen + 8
        val dnsLen = udpLen - 8
        if (dnsStart + dnsLen > buf.limit()) return

        val dns = ByteArray(dnsLen)
        System.arraycopy(arr, dnsStart, dns, 0, dnsLen)

        val domain = extractQueryName(dns) ?: return
        val payload = if (HostsUpdater.isBlocked(domain)) {
            buildNxdomain(dns)
        } else {
            socket.send(DatagramPacket(dns, dns.size, upstream))
            val r = DatagramPacket(respBuf, respBuf.size)
            socket.receive(r)
            respBuf.copyOf(r.length)
        }
        writeResponse(out, arr, ipHdrLen, srcPort, dstPort, payload)
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
        val b = ByteBuffer.allocate(total)
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
