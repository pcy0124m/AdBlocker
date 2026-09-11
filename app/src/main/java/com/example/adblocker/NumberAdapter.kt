package com.example.adblocker

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

class NumberAdapter(private val onRemove: (String) -> Unit) :
    RecyclerView.Adapter<NumberAdapter.VH>() {

    private val list = mutableListOf<String>()

    fun submit(items: List<String>) {
        list.clear()
        list.addAll(items)
        notifyDataSetChanged()
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvNumber: TextView = itemView.findViewById(R.id.tvNumber)
        val btnRemove: MaterialButton = itemView.findViewById(R.id.btnRemove)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_number, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val n = list[position]
        holder.tvNumber.text = n
        holder.btnRemove.setOnClickListener { onRemove(n) }
    }

    override fun getItemCount(): Int = list.size
}
