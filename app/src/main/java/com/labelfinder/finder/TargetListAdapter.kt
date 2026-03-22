package com.labelfinder.finder

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.labelfinder.R
import com.labelfinder.databinding.ItemFinderTargetBinding

class TargetListAdapter(
    private val onMarkFound: (Int) -> Unit,
    private val onUnmark: (Int) -> Unit
) : ListAdapter<SearchTarget, TargetListAdapter.ViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<SearchTarget>() {
            override fun areItemsTheSame(a: SearchTarget, b: SearchTarget) =
                a.barcode == b.barcode && a.colorIndex == b.colorIndex
            override fun areContentsTheSame(a: SearchTarget, b: SearchTarget) = a == b
        }
    }

    class ViewHolder(val binding: ItemFinderTargetBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFinderTargetBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val target = getItem(position)
        val b = holder.binding
        val color = FinderActivity.TARGET_COLORS[target.colorIndex % FinderActivity.TARGET_COLORS.size]

        // Color dot
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        b.colorDot.background = dot

        // Barcode text
        b.barcodeText.text = target.barcode

        when (target.status) {
            TargetStatus.SEARCHING -> {
                b.barcodeText.alpha = 0.6f
                b.statusText.text = "Searching\u2026"
                b.statusText.visibility = View.VISIBLE
                b.markFoundButton.visibility = View.GONE
                b.undoButton.visibility = View.GONE
            }
            TargetStatus.SPOTTED -> {
                b.barcodeText.alpha = 1.0f
                b.statusText.visibility = View.GONE
                b.markFoundButton.visibility = View.VISIBLE
                b.markFoundButton.setOnClickListener { onMarkFound(position) }
                b.undoButton.visibility = View.GONE
            }
            TargetStatus.FOUND -> {
                b.barcodeText.alpha = 0.5f
                b.barcodeText.text = "\u2713 ${target.barcode}"
                b.statusText.visibility = View.GONE
                b.markFoundButton.visibility = View.GONE
                b.undoButton.visibility = View.VISIBLE
                b.undoButton.setOnClickListener { onUnmark(position) }
            }
        }
    }
}
