package com.example.adblocker

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.adblocker.util.StorageHelper
import com.google.android.material.button.MaterialButton

/**
 * 手机优化页：主操作为「去系统清理手机垃圾」（跳转系统释放空间，由系统清全机缓存），
 * 辅以本应用自清（立竿见影）+ 各 App 缓存占用排行 + 后台进程体检。
 *
 * 仅做真实有效的事（见 StorageHelper 说明）：普通应用无权直接清其它 App 缓存，所以走系统入口；
 * 不提供「一键杀后台」式的伪加速。
 */
class PhoneOptimizeActivity : AppCompatActivity() {

    private lateinit var tvPhoneEstimate: TextView
    private lateinit var btnCleanPhone: MaterialButton
    private lateinit var tvSelfCache: TextView
    private lateinit var tvSelfFreed: TextView
    private lateinit var tvCacheEmpty: TextView
    private lateinit var tvProcess: TextView
    private lateinit var rvCache: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_optimize)

        tvPhoneEstimate = findViewById(R.id.tvPhoneEstimate)
        btnCleanPhone = findViewById(R.id.btnCleanPhone)
        tvSelfCache = findViewById(R.id.tvSelfCache)
        tvSelfFreed = findViewById(R.id.tvSelfFreed)
        tvCacheEmpty = findViewById(R.id.tvCacheEmpty)
        tvProcess = findViewById(R.id.tvProcess)
        rvCache = findViewById(R.id.rvCache)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        // 主操作：跳转系统「释放空间」，由系统真正清理全机缓存（普通应用无法直接清其它 App）
        btnCleanPhone.setOnClickListener {
            startActivity(StorageHelper.phoneCleanerIntent(this))
        }
        findViewById<MaterialButton>(R.id.btnClearSelf).setOnClickListener { clearSelf() }

        refresh()
    }

    private fun refresh() {
        // 手机可清理缓存总量（估算）
        tvPhoneEstimate.text = getString(
            R.string.opt_phone_estimate,
            StorageHelper.formatSize(this, StorageHelper.totalCacheBytes(this))
        )

        tvSelfCache.text = getString(
            R.string.opt_self_cache_current,
            StorageHelper.formatSize(this, StorageHelper.selfCacheSize(this))
        )

        val list = StorageHelper.appCacheList(this).take(10)
        rvCache.layoutManager = LinearLayoutManager(this)
        rvCache.adapter = StorageAdapter(list)
        tvCacheEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE

        tvProcess.text = getString(
            R.string.opt_process_count,
            StorageHelper.runningProcessCount(this)
        )
    }

    private fun clearSelf() {
        val freed = StorageHelper.clearSelfCache(this)
        val text = StorageHelper.formatSize(this, freed)
        tvSelfFreed.text = getString(R.string.opt_self_freed, text)
        toast(getString(R.string.opt_self_cleared, text))
        refresh()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
