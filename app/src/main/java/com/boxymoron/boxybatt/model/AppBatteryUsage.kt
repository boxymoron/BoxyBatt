package com.boxymoron.boxybatt.model

import android.graphics.drawable.Drawable

/**
 * Represents per-app battery usage data derived from UsageStatsManager.
 * Includes estimated drain (mAh) correlated with current draw rate.
 */
data class AppBatteryUsage(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val uid: Int,
    var foregroundTimeMs: Long = 0L,
    var backgroundTimeMs: Long = 0L,
    var lastTimeUsed: Long = 0L,
    var launchCount: Int = 0,
    var isSystemApp: Boolean = false,
    var usagePercent: Float = 0f,
    var estimatedDrainMAh: Double = 0.0,  // estimated battery drain in mAh
    var cpuTimeMs: Long = 0L,             // total CPU time (user + system)
    var ramMB: Long = 0L                  // PSS RAM usage in MB
) {
    val totalTimeMs: Long
        get() = foregroundTimeMs + backgroundTimeMs

    fun formatDuration(ms: Long): String {
        if (ms <= 0) return "0s"
        val seconds = ms / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        return when {
            hours > 0 -> "${hours}h ${minutes % 60}m"
            minutes > 0 -> "${minutes}m ${seconds % 60}s"
            else -> "${seconds}s"
        }
    }

    val foregroundDisplay: String get() = formatDuration(foregroundTimeMs)
    val backgroundDisplay: String get() = formatDuration(backgroundTimeMs)
    val totalDisplay: String get() = formatDuration(totalTimeMs)

    val cpuTimeDisplay: String get() = formatDuration(cpuTimeMs)

    val ramDisplay: String
        get() = when {
            ramMB <= 0 -> "—"
            ramMB >= 1024 -> String.format("%.1f GB", ramMB / 1024.0)
            else -> "$ramMB MB"
        }

    val drainDisplay: String
        get() {
            if (estimatedDrainMAh <= 0) return "< 1 mAh"
            return when {
                estimatedDrainMAh >= 1000 -> String.format("%.1f Ah", estimatedDrainMAh / 1000)
                estimatedDrainMAh >= 10 -> String.format("%.0f mAh", estimatedDrainMAh)
                else -> String.format("%.1f mAh", estimatedDrainMAh)
            }
        }

    val usageLevel: UsageLevel
        get() = when {
            estimatedDrainMAh > 100 -> UsageLevel.HEAVY     // > 100 mAh
            totalTimeMs > 3600_000L -> UsageLevel.HEAVY      // > 1 hour
            estimatedDrainMAh > 20 -> UsageLevel.MODERATE    // > 20 mAh
            totalTimeMs > 600_000L -> UsageLevel.MODERATE     // > 10 minutes
            else -> UsageLevel.LIGHT
        }

    enum class UsageLevel { HEAVY, MODERATE, LIGHT }
}
