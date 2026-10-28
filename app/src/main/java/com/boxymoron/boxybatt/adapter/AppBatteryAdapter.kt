package com.boxymoron.boxybatt.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.boxymoron.boxybatt.R
import com.boxymoron.boxybatt.model.AppBatteryUsage

/**
 * RecyclerView adapter for per-app battery usage list.
 * Shows app icon, name, usage percentage, foreground/background time.
 */
class AppBatteryAdapter(
    private val onItemClick: (AppBatteryUsage) -> Unit
) : RecyclerView.Adapter<AppBatteryAdapter.AppViewHolder>() {

    private val apps = mutableListOf<AppBatteryUsage>()

    fun submitList(newApps: List<AppBatteryUsage>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = apps.size
            override fun getNewListSize() = newApps.size
            override fun areItemsTheSame(o: Int, n: Int) =
                apps[o].packageName == newApps[n].packageName
            override fun areContentsTheSame(o: Int, n: Int) =
                apps[o].foregroundTimeMs == newApps[n].foregroundTimeMs &&
                apps[o].usagePercent == newApps[n].usagePercent
        })
        apps.clear()
        apps.addAll(newApps)
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_battery, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(apps[position])
    }

    override fun getItemCount() = apps.size

    inner class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val statusBar: View = view.findViewById(R.id.status_bar)
        private val ivIcon: ImageView = view.findViewById(R.id.iv_icon)
        private val tvName: TextView = view.findViewById(R.id.tv_name)
        private val tvPackage: TextView = view.findViewById(R.id.tv_package)
        private val tvUsagePct: TextView = view.findViewById(R.id.tv_usage_pct)
        private val tvForeground: TextView = view.findViewById(R.id.tv_foreground)
        private val tvBackground: TextView = view.findViewById(R.id.tv_background)
        private val tvDrain: TextView = view.findViewById(R.id.tv_drain)
        private val tvCpuTime: TextView = view.findViewById(R.id.tv_cpu_time)
        private val tvRam: TextView = view.findViewById(R.id.tv_ram)
        private val tvLastUsed: TextView = view.findViewById(R.id.tv_last_used)

        fun bind(app: AppBatteryUsage) {
            tvName.text = app.appName
            tvPackage.text = app.packageName

            if (app.icon != null) ivIcon.setImageDrawable(app.icon)
            else ivIcon.setImageResource(R.drawable.ic_default_app)

            // Usage percentage
            tvUsagePct.text = String.format("%.1f%%", app.usagePercent)

            // Color based on usage level
            val statusColor = when (app.usageLevel) {
                AppBatteryUsage.UsageLevel.HEAVY -> R.color.accent_red
                AppBatteryUsage.UsageLevel.MODERATE -> R.color.accent_orange
                AppBatteryUsage.UsageLevel.LIGHT -> R.color.accent_green
            }
            statusBar.setBackgroundColor(ContextCompat.getColor(itemView.context, statusColor))
            tvUsagePct.setTextColor(ContextCompat.getColor(itemView.context, statusColor))

            // Time stats
            tvForeground.text = app.foregroundDisplay
            tvBackground.text = app.backgroundDisplay
            tvDrain.text = app.drainDisplay
            tvCpuTime.text = app.cpuTimeDisplay
            tvRam.text = app.ramDisplay

            // Last used
            if (app.lastTimeUsed > 0) {
                val ago = System.currentTimeMillis() - app.lastTimeUsed
                tvLastUsed.text = formatAgo(ago)
            } else {
                tvLastUsed.text = "—"
            }

            itemView.setOnClickListener { onItemClick(app) }
        }

        private fun formatAgo(ms: Long): String {
            val minutes = ms / 60_000
            val hours = minutes / 60
            val days = hours / 24
            return when {
                days > 0 -> "${days}d ago"
                hours > 0 -> "${hours}h ago"
                minutes > 0 -> "${minutes}m ago"
                else -> "just now"
            }
        }
    }
}
