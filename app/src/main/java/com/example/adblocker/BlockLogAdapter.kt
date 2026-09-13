package com.example.adblocker

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.adblocker.data.BlockType
import com.example.adblocker.data.BlockedEvent
import com.example.adblocker.util.BlockLog

/**
 * 拦截记录列表适配器（电话 / 短信共用同一套行布局）。
 *
 * 数据由 BlockLog 从 Room 同步读取，因此这里的 submit() 只做整体替换，
 * 记录条数不多（最多 50 条），不需要 DiffUtil。
 */
class BlockLogAdapter : RecyclerView.Adapter<BlockLogAdapter.VH>() {

    private val list = mutableListOf<BlockedEvent>()

    fun submit(items: List<BlockedEvent>) {
        list.clear()
        list.addAll(items)
        notifyDataSetChanged()
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvType: TextView = itemView.findViewById(R.id.tvLogType)
        val tvNumber: TextView = itemView.findViewById(R.id.tvLogNumber)
        val tvCount: TextView = itemView.findViewById(R.id.tvLogCount)
        val tvContent: TextView = itemView.findViewById(R.id.tvLogContent)
        val tvReason: TextView = itemView.findViewById(R.id.tvLogReason)
        val tvTime: TextView = itemView.findViewById(R.id.tvLogTime)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_block_log, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = list[position]
        val ctx = holder.itemView.context
        val isCall = item.type == BlockType.CALL

        // 类型徽标：电话蓝、短信黄，和上方状态卡配色保持一致
        holder.tvType.setText(if (isCall) R.string.log_type_call else R.string.log_type_sms)
        holder.tvType.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(ctx, if (isCall) R.color.icon_call else R.color.icon_sms)
        )

        holder.tvNumber.text =
            if (item.number.isBlank()) ctx.getString(R.string.log_unknown_number) else item.number

        if (item.count > 1) {
            holder.tvCount.visibility = View.VISIBLE
            holder.tvCount.text = ctx.getString(R.string.log_count, item.count)
        } else {
            holder.tvCount.visibility = View.GONE
        }

        if (item.content.isBlank()) {
            holder.tvContent.visibility = View.GONE
        } else {
            holder.tvContent.visibility = View.VISIBLE
            holder.tvContent.text = item.content
        }

        holder.tvReason.text = when (item.reason) {
            BlockLog.REASON_BLACKLIST -> ctx.getString(R.string.log_reason_blacklist)
            BlockLog.REASON_KEYWORD -> ctx.getString(R.string.log_reason_keyword)
            else -> ""
        }
        holder.tvTime.text = BlockLog.timeText(item.time)
    }

    override fun getItemCount(): Int = list.size
}
