package com.example.adblocker

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import androidx.recyclerview.widget.RecyclerView
import com.example.adblocker.util.HostsUpdater
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var btnVpn: MaterialButton
    private lateinit var btnCall: MaterialButton
    private lateinit var btnSms: MaterialButton
    private lateinit var btnAdd: MaterialButton
    private lateinit var btnUpdateRules: MaterialButton
    private lateinit var etNumber: TextInputEditText
    private lateinit var rvNumbers: RecyclerView
    private lateinit var adapter: NumberAdapter

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
        etNumber = findViewById(R.id.etNumber)
        rvNumbers = findViewById(R.id.rvNumbers)

        adapter = NumberAdapter { number -> removeNumber(number) }
        rvNumbers.layoutManager = LinearLayoutManager(this)
        rvNumbers.adapter = adapter
        refreshList()

        btnVpn.setOnClickListener { toggleVpn() }
        btnCall.setOnClickListener { requestCallScreening() }
        btnSms.setOnClickListener { requestSmsRole() }
        btnAdd.setOnClickListener { addNumber() }
        btnUpdateRules.setOnClickListener { updateRules() }

        updateVpnButton()
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
        // ContextCompat 在 API < 26 上回退到 startService，避免 NoSuchMethodError
        ContextCompat.startForegroundService(this, Intent(this, AdBlockVpnService::class.java))
        updateVpnButton()
    }

    private fun updateVpnButton() {
        btnVpn.setText(
            if (AdBlockVpnService.running) R.string.stop_vpn else R.string.start_vpn
        )
    }

    // ---------- 广告规则在线更新 ----------
    private fun updateRules() {
        toast(getString(R.string.updating_rules))
        scope.launch {
            try {
                HostsUpdater.refresh()
                val n = HostsUpdater.size()
                withContext(Dispatchers.Main) {
                    toast(getString(R.string.rules_updated, n))
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
            if (!rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
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
    private fun requestSmsRole() {
        // 非默认短信 App 的「尽力拦」依赖 RECEIVE_SMS 运行时授权（Android 6+ 危险权限），
        // 先确保拿到它，abortBroadcast 路径才会真正触发。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECEIVE_SMS),
                REQ_SMS_PERM
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            if (!rm.isRoleHeld(RoleManager.ROLE_SMS)) {
                startActivityForResult(
                    rm.createRequestRoleIntent(RoleManager.ROLE_SMS),
                    REQ_SMS
                )
            } else {
                toast("已设为默认短信 App，可拦截垃圾短信")
            }
        } else {
            toast("请到系统设置中将本 App 设为默认短信应用")
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
        adapter.submit(BlockListManager.getAll())
    }

    // ---------- 回调 ----------
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_VPN -> if (resultCode == RESULT_OK) startVpn()
            REQ_CALL -> toast("电话拦截权限已设置")
            REQ_SMS -> toast("默认短信权限已设置")
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
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            toast("已获得短信接收权限，非默认 App 也能尽力拦截")
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
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
