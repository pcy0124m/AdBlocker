package com.example.adblocker

import android.Manifest
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.example.adblocker.data.BlockType
import com.example.adblocker.util.BlockLog
import com.example.adblocker.util.CrashHandler
import com.example.adblocker.util.HostsUpdater
import com.example.adblocker.util.Prefs
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var btnVpn: MaterialButton
    private lateinit var btnCall: MaterialButton
    private lateinit var btnSms: MaterialButton
    private lateinit var btnAdd: MaterialButton
    private lateinit var btnUpdateRules: MaterialButton
    private lateinit var btnAddWhitelist: MaterialButton
    private lateinit var switchAutoStart: MaterialSwitch
    private lateinit var switchPause: MaterialSwitch
    private lateinit var etNumber: TextInputEditText
    private lateinit var etWhitelist: TextInputEditText
    private lateinit var rvNumbers: RecyclerView
    private lateinit var rvWhitelist: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tvWhitelistEmpty: TextView
    private lateinit var tvStatusAd: TextView
    private lateinit var tvStatusCall: TextView
    private lateinit var tvStatusSms: TextView
    private lateinit var tvStatsBlocked: TextView
    private lateinit var tvStatsRules: TextView
    private lateinit var tvStatsNote: TextView
    private lateinit var cardCrash: MaterialCardView
    private lateinit var tvCrashLog: TextView
    private lateinit var rvLogs: RecyclerView
    private lateinit var tvLogsEmpty: TextView
    private lateinit var tvLogSummary: TextView
    private lateinit var chipGroupLogs: ChipGroup
    private lateinit var adapter: NumberAdapter
    private lateinit var whitelistAdapter: NumberAdapter
    private lateinit var logAdapter: BlockLogAdapter

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        BlockListManager.init(this)
        HostsUpdater.loadLocal(this)

        btnVpn = findViewById(R.id.btnVpn)
        btnCall = findViewById(R.id.btnCall)
        btnSms = findViewById(R.id.btnSms)
        btnAdd = findViewById(R.id.btnAdd)
        btnUpdateRules = findViewById(R.id.btnUpdateRules)
        btnAddWhitelist = findViewById(R.id.btnAddWhitelist)
        switchAutoStart = findViewById(R.id.switchAutoStart)
        switchPause = findViewById(R.id.switchPause)
        etNumber = findViewById(R.id.etNumber)
        etWhitelist = findViewById(R.id.etWhitelist)
        rvNumbers = findViewById(R.id.rvNumbers)
        rvWhitelist = findViewById(R.id.rvWhitelist)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvWhitelistEmpty = findViewById(R.id.tvWhitelistEmpty)
        tvStatusAd = findViewById(R.id.tvStatusAd)
        tvStatusCall = findViewById(R.id.tvStatusCall)
        tvStatusSms = findViewById(R.id.tvStatusSms)
        tvStatsBlocked = findViewById(R.id.tvStatsBlocked)
        tvStatsRules = findViewById(R.id.tvStatsRules)
        tvStatsNote = findViewById(R.id.tvStatsNote)
        cardCrash = findViewById(R.id.cardCrash)
        tvCrashLog = findViewById(R.id.tvCrashLog)

        findViewById<MaterialButton>(R.id.btnCopyCrash).setOnClickListener { copyCrashLog() }
        findViewById<MaterialButton>(R.id.btnClearCrash).setOnClickListener {
            CrashHandler.clear(this)
            refreshCrashCard()
        }

        adapter = NumberAdapter { number -> removeNumber(number) }
        rvNumbers.layoutManager = LinearLayoutManager(this)
        rvNumbers.adapter = adapter
        refreshList()

        whitelistAdapter = NumberAdapter { domain -> removeWhitelist(domain) }
        rvWhitelist.layoutManager = LinearLayoutManager(this)
        rvWhitelist.adapter = whitelistAdapter
        refreshWhitelist()

        // 拦截记录：电话 / 短信共用一份列表，用 Chip 做筛选
        rvLogs = findViewById(R.id.rvLogs)
        tvLogsEmpty = findViewById(R.id.tvLogsEmpty)
        tvLogSummary = findViewById(R.id.tvLogSummary)
        chipGroupLogs = findViewById(R.id.chipGroupLogs)
        logAdapter = BlockLogAdapter()
        rvLogs.layoutManager = LinearLayoutManager(this)
        rvLogs.adapter = logAdapter
        refreshLogs()
        chipGroupLogs.setOnCheckedStateChangeListener { _, _ -> refreshLogs(currentLogFilter()) }
        findViewById<MaterialButton>(R.id.btnClearLogs).setOnClickListener { confirmClearLogs() }

        btnVpn.setOnClickListener { toggleVpn() }
        btnCall.setOnClickListener { requestCallScreening() }
        btnSms.setOnClickListener { requestSmsRole() }
        btnAdd.setOnClickListener { addNumber() }
        btnUpdateRules.setOnClickListener { updateRules() }
        btnAddWhitelist.setOnClickListener { addWhitelist() }

        // 先设置初始状态再挂监听，避免初始化时误触发 Toast
        switchAutoStart.isChecked = Prefs.isAutoStartVpn(this)
        switchAutoStart.setOnCheckedChangeListener { _, checked ->
            Prefs.setAutoStartVpn(this, checked)
            toast(
                if (checked) getString(R.string.auto_start_enabled)
                else getString(R.string.auto_start_disabled)
            )
        }

        switchPause.isChecked = Prefs.isBlockingPaused(this)
        switchPause.setOnCheckedChangeListener { _, checked ->
            Prefs.setBlockingPaused(this, checked)
            toast(
                if (checked) getString(R.string.pause_enabled)
                else getString(R.string.pause_disabled)
            )
            refreshStats()
        }

        // 手机优化入口：清理缓存 / 缓存占用排行 / 后台进程体检
        findViewById<MaterialCardView>(R.id.cardOptimize)
            .setOnClickListener {
                startActivity(Intent(this, PhoneOptimizeActivity::class.java))
            }

        updateVpnButton()
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置页返回时，角色状态可能已变化，这里刷新一次
        updateVpnButton()
        refreshCrashCard()
        // 拦截记录由服务和广播接收器在后台写入，每次回到前台刷新一下
        refreshLogs(currentLogFilter())
    }

    override fun onPause() {
        // 把本会话拦截数结算进累计值，保证切到后台后统计不丢
        HostsUpdater.flushSession(this)
        super.onPause()
    }

    // ---------- 运行状态总览 ----------
    private fun refreshStatus() {
        setStatus(tvStatusAd, AdBlockVpnService.running)
        setStatus(tvStatusCall, isRoleHeld(RoleManager.ROLE_CALL_SCREENING))

        // 短信是三态：已设默认短信 App（彻底拦）/ 仅授权（尽力拦）/ 未开启
        when {
            isRoleHeld(RoleManager.ROLE_SMS) -> setStatus(tvStatusSms, true)
            hasReceiveSmsPermission() -> {
                tvStatusSms.setText(R.string.status_partial)
                tvStatusSms.setTextColor(ContextCompat.getColor(this, R.color.icon_sms))
            }
            else -> setStatus(tvStatusSms, false)
        }
    }

    private fun hasReceiveSmsPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

    private fun isRoleHeld(role: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val rm = getSystemService(RoleManager::class.java) ?: return false
        return rm.isRoleHeld(role)
    }

    private fun setStatus(tv: TextView, on: Boolean) {
        tv.setText(if (on) R.string.status_on else R.string.status_off)
        tv.setTextColor(
            ContextCompat.getColor(this, if (on) R.color.status_on else R.color.status_off)
        )
    }

    // ---------- 拦截统计 ----------
    private fun refreshStats() {
        val total = Prefs.totalBlocked(this) + HostsUpdater.sessionBlockedCount()
        tvStatsBlocked.text = getString(R.string.stats_blocked_value, total)
        tvStatsRules.text = getString(R.string.stats_rules_value, HostsUpdater.size())
        tvStatsNote.setText(
            if (Prefs.isBlockingPaused(this)) R.string.stats_paused
            else R.string.stats_user_hint
        )
    }

    // ---------- 广告拦截（VPN） ----------
    private fun toggleVpn() {
        if (AdBlockVpnService.running) {
            stopService(Intent(this, AdBlockVpnService::class.java))
            updateVpnButton()
        } else {
            val prep = VpnService.prepare(this)
            if (prep != null) {
                startActivityForResult(prep, REQ_VPN)
            } else {
                startVpn()
            }
        }
    }

    private fun startVpn() {
        // 部分国产 ROM（ColorOS / MIUI 等）会限制后台拉起前台服务，抛出的
        // ForegroundServiceStartNotAllowedException / SecurityException
        // 若不捕获，就会表现为「点一下按钮就闪退」。
        try {
            // ContextCompat 在 API < 26 上回退到 startService，避免 NoSuchMethodError
            ContextCompat.startForegroundService(this, Intent(this, AdBlockVpnService::class.java))
        } catch (_: Exception) {
            toast(getString(R.string.vpn_start_failed))
        }
        updateVpnButton()
    }

    private fun updateVpnButton() {
        btnVpn.setText(
            if (AdBlockVpnService.running) R.string.stop_vpn else R.string.start_vpn
        )
        refreshStatus()
        refreshStats()
    }

    // ---------- 广告规则在线更新 ----------
    private fun updateRules() {
        toast(getString(R.string.updating_rules))
        scope.launch {
            try {
                HostsUpdater.refresh()
                val n = HostsUpdater.size()
                withContext(Dispatchers.Main) {
                    DnsCache.clear()
                    toast(getString(R.string.rules_updated, n))
                    refreshStats()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toast(getString(R.string.rules_update_failed))
                }
            }
        }
    }

    // ---------- 骚扰电话拦截（CallScreening） ----------
    private fun requestCallScreening() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && !rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
                startActivityForResult(
                    rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING),
                    REQ_CALL
                )
            } else {
                toast("电话拦截已开启")
            }
        } else {
            toast("电话拦截需 Android 10+（CallScreeningService）")
        }
    }

    // ---------- 垃圾短信拦截（默认短信 App / 非默认尽力拦） ----------
    /**
     * 短信拦截有两条路，代价不同，所以先弹窗讲清楚再让用户选：
     *   A. 设为默认短信 App —— 唯一能"彻底拦截"的方式（系统硬性要求）
     *   B. 仅授权 RECEIVE_SMS —— 只能"尽力拦"，不改变系统默认短信 App
     */
    private fun requestSmsRole() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sms_dialog_title)
            .setMessage(R.string.sms_dialog_message)
            .setPositiveButton(R.string.sms_dialog_set_default) { _, _ -> setDefaultSmsApp() }
            .setNeutralButton(R.string.sms_dialog_best_effort) { _, _ -> enableBestEffortSms() }
            .setNegativeButton(R.string.sms_dialog_cancel, null)
            .show()
    }

    /** 路线 A：申请 ROLE_SMS（成为默认短信 App）。 */
    private fun setDefaultSmsApp() {
        ensureSmsPermissions()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Android 10 以下没有 RoleManager，只能引导到系统设置手动选择
            toast(getString(R.string.sms_role_unavailable))
            openDefaultAppsSettings()
            return
        }

        val rm = getSystemService(RoleManager::class.java)
        if (rm == null || !rm.isRoleAvailable(RoleManager.ROLE_SMS)) {
            toast(getString(R.string.sms_role_unavailable))
            openDefaultAppsSettings()
            return
        }
        if (rm.isRoleHeld(RoleManager.ROLE_SMS)) {
            toast(getString(R.string.sms_role_already))
            refreshStatus()
            return
        }
        startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_SMS), REQ_SMS)
    }

    /** 路线 B：只拿 RECEIVE_SMS，保持系统默认短信 App 不变，尽力拦截。 */
    private fun enableBestEffortSms() {
        ensureSmsPermissions()
        toast(getString(R.string.sms_best_effort_enabled))
        refreshStatus()
    }

    /**
     * 短信拦截所需运行时权限：
     *   - RECEIVE_SMS：非默认短信 App 的「尽力拦」路径依赖它
     *   - POST_NOTIFICATIONS（Android 13+）：成为默认短信 App 后，放行短信的通知需要它
     */
    private fun ensureSmsPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECEIVE_SMS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_SMS_PERM)
        }
    }

    /** 兜底：跳到系统「默认应用」设置页，部分 ROM（MIUI / HarmonyOS）只认手动设置。 */
    private fun openDefaultAppsSettings() {
        try {
            val action =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                    Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS
                else Settings.ACTION_SETTINGS
            startActivity(Intent(action))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    // ---------- 黑名单管理 ----------
    private fun addNumber() {
        val n = etNumber.text.toString().trim()
        if (n.isEmpty()) {
            toast("请输入号码")
            return
        }
        scope.launch {
            BlockListManager.add(n)
            etNumber.setText("")
            refreshList()
        }
    }

    private fun removeNumber(n: String) {
        scope.launch {
            BlockListManager.remove(n)
            refreshList()
        }
    }

    private fun refreshList() {
        val items = BlockListManager.getAll()
        adapter.submit(items)
        tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    // ---------- 域名白名单管理 ----------
    private fun addWhitelist() {
        val raw = etWhitelist.text.toString().trim()
        if (raw.isEmpty()) {
            toast(getString(R.string.whitelist_invalid))
            return
        }
        if (HostsUpdater.addWhitelist(this, raw)) {
            etWhitelist.setText("")
            toast(getString(R.string.whitelist_added, raw))
            refreshWhitelist()
            refreshStats()
        } else {
            toast(getString(R.string.whitelist_invalid))
        }
    }

    private fun removeWhitelist(domain: String) {
        HostsUpdater.removeWhitelist(this, domain)
        refreshWhitelist()
        refreshStats()
    }

    private fun refreshWhitelist() {
        val items = HostsUpdater.whiteList()
        whitelistAdapter.submit(items)
        tvWhitelistEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    // ---------- 拦截记录（电话 / 短信） ----------
    /** 当前筛选的拦截类型，null = 全部。 */
    private fun currentLogFilter(): Int? = when (chipGroupLogs.checkedChipId) {
        R.id.chipCall -> BlockType.CALL
        R.id.chipSms -> BlockType.SMS
        else -> null
    }

    private fun refreshLogs(type: Int? = null) {
        val items = BlockLog.recent(this, type)
        logAdapter.submit(items)

        val total = BlockLog.count(this)
        val callCount = BlockLog.count(this, BlockType.CALL)
        val smsCount = BlockLog.count(this, BlockType.SMS)

        if (total == 0) {
            tvLogSummary.setText(R.string.logs_summary_empty)
        } else {
            tvLogSummary.text = getString(R.string.logs_summary, total, callCount, smsCount)
        }

        if (items.isEmpty()) {
            tvLogsEmpty.visibility = View.VISIBLE
            // 区分「一条都没拦到」和「只是当前筛选为空」，提示更准确
            tvLogsEmpty.setText(if (total == 0) R.string.empty_logs else R.string.empty_logs_filtered)
        } else {
            tvLogsEmpty.visibility = View.GONE
        }
    }

    private fun confirmClearLogs() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.logs_clear)
            .setMessage(R.string.logs_clear_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                BlockLog.clear(this)
                refreshLogs(currentLogFilter())
                toast(getString(R.string.logs_cleared))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------- 回调 ----------
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_VPN -> if (resultCode == RESULT_OK) startVpn() else updateVpnButton()
            REQ_CALL -> {
                toast("电话拦截权限已设置")
                refreshStatus()
            }
            REQ_SMS -> {
                // 不能只看 resultCode：部分 ROM 会返回 RESULT_OK 但实际未授予，
                // 所以以 isRoleHeld 的真实状态为准。
                if (isRoleHeld(RoleManager.ROLE_SMS)) {
                    toast(getString(R.string.sms_role_granted))
                } else {
                    toast(getString(R.string.sms_role_denied))
                }
                refreshStatus()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_SMS_PERM &&
            grantResults.any { it == PackageManager.PERMISSION_GRANTED }
        ) {
            toast("短信拦截所需权限已就绪")
        }
        refreshStatus()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---------- 崩溃日志 ----------
    private fun refreshCrashCard() {
        val log = CrashHandler.read(this)
        if (log.isNullOrBlank()) {
            cardCrash.visibility = View.GONE
        } else {
            cardCrash.visibility = View.VISIBLE
            tvCrashLog.text = log
        }
    }

    private fun copyCrashLog() {
        val log = CrashHandler.read(this) ?: return
        try {
            val cm = getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("adblocker-crash", log))
            toast(getString(R.string.crash_copied))
        } catch (_: Exception) {
            // 极少数 ROM 限制剪贴板访问，忽略即可
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQ_VPN = 1
        private const val REQ_CALL = 2
        private const val REQ_SMS = 3
        private const val REQ_SMS_PERM = 4
    }
}
