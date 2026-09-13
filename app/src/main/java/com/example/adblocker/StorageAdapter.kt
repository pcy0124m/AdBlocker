package com.example.adblocker

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.adblocker.util.StorageHelper

/** 「手机优化」页的缓存占用排行列表适配器。 */
class StorageAdapter(private val items: List<StorageHelper.AppCacheInfo>) :
    RecyclerView.Adapter<StorageAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tvAppName)
        val size: TextView = view.findViewById(R.id.tvCacheSize)
        val pkg: TextView = view.findViewById(R.id.tvPkg)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_cache, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val it = items[position]
        val ctx: Context = holder.itemView.context
        val self = it.packageName == ctx.packageName
        holder.name.text = if (self) it.appName + "（本应用）" else it.appName
        holder.size.text = StorageHelper.formatSize(ctx, it.cacheBytes)
        holder.pkg.text = it.packageName
        // 点击跳转到该 App 的系统设置详情页，便于手动清理缓存
        holder.itemView.setOnClickListener {
            try {
                ctx.startActivity(StorageHelper.appDetailsIntent(it.packageName))
            } catch (_: Exception) {
            }
        }
    }

    override fun getItemCount(): Int = items.size
}
