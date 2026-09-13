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
 * 手机优化页：清理本应用缓存 + 各 App 缓存占用排行 + 后台进程体检。
 *
 * 仅做真实有效的事（见 StorageHelper 说明）：不提供「一键杀后台」式的伪加速。
 */
class PhoneOptimizeActivity : AppCompatActivity() {

    private lateinit var tvSelfCache: TextView
    private lateinit var tvSelfFreed: TextView
    private lateinit var tvCacheEmpty: TextView
    private lateinit var tvProcess: TextView
    private lateinit var rvCache: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_optimize)

        tvSelfCache = findViewById(R.id.tvSelfCache)
        tvSelfFreed = findViewById(R.id.tvSelfFreed)
        tvCacheEmpty = findViewById(R.id.tvCacheEmpty)
        tvProcess = findViewById(R.id.tvProcess)
        rvCache = findViewById(R.id.rvCache)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btnClearSelf).setOnClickListener { clearSelf() }

        refresh()
    }

    private fun refresh() {
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
