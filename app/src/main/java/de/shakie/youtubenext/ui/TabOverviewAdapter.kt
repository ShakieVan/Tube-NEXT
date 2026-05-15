package de.shakie.youtubenext.ui

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import de.shakie.youtubenext.R

data class TabOverviewItem(
    val id: String,
    val title: String,
    val preview: Bitmap?,
    val isActive: Boolean
)

class TabOverviewAdapter(
    private val onTabClick: (String) -> Unit,
    private val onTabClose: (String) -> Unit
) : RecyclerView.Adapter<TabOverviewAdapter.TabViewHolder>() {

    private val items = mutableListOf<TabOverviewItem>()

    fun submitList(newItems: List<TabOverviewItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun updatePreview(tabId: String, bitmap: Bitmap) {
        val index = items.indexOfFirst { it.id == tabId }
        if (index < 0) return
        items[index] = items[index].copy(preview = bitmap)
        notifyItemChanged(index)
    }

    fun moveItem(fromPosition: Int, toPosition: Int): Boolean {
        if (fromPosition == toPosition) return false
        if (fromPosition !in items.indices || toPosition !in items.indices) return false
        val moved = items.removeAt(fromPosition)
        items.add(toPosition, moved)
        notifyItemMoved(fromPosition, toPosition)
        return true
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tab_overview, parent, false)
        return TabViewHolder(view)
    }

    override fun onBindViewHolder(holder: TabViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class TabViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val root: LinearLayout = itemView.findViewById(R.id.tabItemRoot)
        private val preview: ImageView = itemView.findViewById(R.id.tabPreview)
        private val title: TextView = itemView.findViewById(R.id.tabTitle)
        private val closeButton: ImageButton = itemView.findViewById(R.id.tabCloseButton)

        fun bind(item: TabOverviewItem) {
            title.text = item.title
            if (item.preview != null) {
                preview.setImageBitmap(item.preview)
            } else {
                preview.setImageDrawable(null)
            }
            root.setBackgroundColor(
                if (item.isActive) {
                    ContextCompat.getColor(itemView.context, R.color.yt_tab_active)
                } else {
                    ContextCompat.getColor(itemView.context, android.R.color.transparent)
                }
            )
            root.setOnClickListener { onTabClick(item.id) }
            closeButton.setOnClickListener { onTabClose(item.id) }
        }
    }
}
