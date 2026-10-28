package com.boxymoron.boxybatt

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.boxymoron.boxybatt.model.AppBatteryUsage
import com.boxymoron.boxybatt.util.AppUsageCollector

/**
 * Detailed per-app battery usage screen.
 * Shows comprehensive usage metrics for a single app.
 */
class AppBatteryDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PACKAGE_NAME = "pkg"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_battery_detail)

        val pkgName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: run {
            finish(); return
        }

        // Back
        findViewById<android.widget.ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        // Get app info
        val pm = packageManager
        val appInfo = try {
            pm.getApplicationInfo(pkgName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            finish(); return
        }

        val appName = pm.getApplicationLabel(appInfo).toString()
        val icon = try { pm.getApplicationIcon(pkgName) } catch (_: Exception) { null }
        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

        // Title
        findViewById<TextView>(R.id.tv_title).text = appName

        // App identity
        val ivIcon = findViewById<ImageView>(R.id.iv_icon)
        if (icon != null) ivIcon.setImageDrawable(icon)
        else ivIcon.setImageResource(R.drawable.ic_default_app)

        findViewById<TextView>(R.id.tv_app_name).text = appName
        findViewById<TextView>(R.id.tv_package).text = pkgName
        findViewById<TextView>(R.id.tv_uid).text = "UID: ${appInfo.uid}"

        // Get usage data
        val collector = AppUsageCollector.getInstance(this)
        val allUsage = collector.getAppUsage()
        val usage = allUsage.find { it.packageName == pkgName }

        if (usage != null) {
            // Usage summary
            val levelLabel = when (usage.usageLevel) {
                AppBatteryUsage.UsageLevel.HEAVY -> "HEAVY USAGE"
                AppBatteryUsage.UsageLevel.MODERATE -> "MODERATE USAGE"
                AppBatteryUsage.UsageLevel.LIGHT -> "LIGHT USAGE"
            }
            val tvSummary = findViewById<TextView>(R.id.tv_usage_summary)
            tvSummary.text = "$levelLabel — ${String.format("%.1f%%", usage.usagePercent)} of screen time"
            val summaryColor = when (usage.usageLevel) {
                AppBatteryUsage.UsageLevel.HEAVY -> R.color.accent_red
                AppBatteryUsage.UsageLevel.MODERATE -> R.color.accent_orange
                AppBatteryUsage.UsageLevel.LIGHT -> R.color.accent_green
            }
            tvSummary.setTextColor(getColor(summaryColor))

            // Time usage
            findViewById<TextView>(R.id.tv_foreground_time).text = usage.foregroundDisplay
            findViewById<TextView>(R.id.tv_background_time).text = usage.backgroundDisplay
            findViewById<TextView>(R.id.tv_total_time).text = usage.totalDisplay

            // Last used
            if (usage.lastTimeUsed > 0) {
                val ago = System.currentTimeMillis() - usage.lastTimeUsed
                val minutes = ago / 60_000
                val hours = minutes / 60
                val days = hours / 24
                val lastUsedText = when {
                    days > 0 -> "$days days ago"
                    hours > 0 -> "$hours hours ago"
                    minutes > 0 -> "$minutes min ago"
                    else -> "just now"
                }
                findViewById<TextView>(R.id.tv_last_used).text = lastUsedText
            } else {
                findViewById<TextView>(R.id.tv_last_used).text = "Unknown"
            }

            // Launch count (placeholder — not directly available from UsageStats)
            findViewById<TextView>(R.id.tv_launch_count).text = "—"
        } else {
            findViewById<TextView>(R.id.tv_usage_summary).text = "NO USAGE DATA — app may not have been used recently"
            findViewById<TextView>(R.id.tv_usage_summary).setTextColor(getColor(R.color.text_tertiary))
            findViewById<TextView>(R.id.tv_foreground_time).text = "0s"
            findViewById<TextView>(R.id.tv_background_time).text = "0s"
            findViewById<TextView>(R.id.tv_total_time).text = "0s"
            findViewById<TextView>(R.id.tv_last_used).text = "Unknown"
            findViewById<TextView>(R.id.tv_launch_count).text = "0"
        }

        // Category
        val category = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            when (appInfo.category) {
                ApplicationInfo.CATEGORY_GAME -> "Game"
                ApplicationInfo.CATEGORY_AUDIO -> "Audio"
                ApplicationInfo.CATEGORY_VIDEO -> "Video"
                ApplicationInfo.CATEGORY_IMAGE -> "Image"
                ApplicationInfo.CATEGORY_SOCIAL -> "Social"
                ApplicationInfo.CATEGORY_NEWS -> "News"
                ApplicationInfo.CATEGORY_MAPS -> "Maps"
                ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Productivity"
                else -> if (isSystem) "System" else "App"
            }
        } else {
            if (isSystem) "System" else "App"
        }
        findViewById<TextView>(R.id.tv_category).text = category
    }
}
