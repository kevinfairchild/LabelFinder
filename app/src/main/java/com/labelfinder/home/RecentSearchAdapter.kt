package com.labelfinder.home

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.labelfinder.R
import com.labelfinder.data.SearchHistory

class RecentSearchAdapter(
    private val onTapToAdd: (String) -> Unit
) : ListAdapter<SearchHistory, RecentSearchAdapter.ViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<SearchHistory>() {
            override fun areItemsTheSame(a: SearchHistory, b: SearchHistory) = a.id == b.id
            override fun areContentsTheSame(a: SearchHistory, b: SearchHistory) = a == b
        }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val barcode: TextView = view.findViewById(R.id.recentBarcode)
        val timestamp: TextView = view.findViewById(R.id.recentTimestamp)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recent_search, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.barcode.text = item.barcode
        holder.barcode.typeface = androidx.core.content.res.ResourcesCompat.getFont(holder.itemView.context, com.labelfinder.R.font.jetbrains_mono) ?: Typeface.MONOSPACE
        holder.timestamp.text = formatTimestamp(item.timestamp)
        holder.itemView.setOnClickListener { onTapToAdd(item.barcode) }
    }

    private fun formatTimestamp(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        val minutes = diff / 60_000
        val hours = diff / 3_600_000
        val days = diff / 86_400_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            hours < 24 -> "${hours}h ago"
            days < 7 -> "${days}d ago"
            else -> java.text.SimpleDateFormat("MM/dd", java.util.Locale.US).format(java.util.Date(timestamp))
        }
    }
}
