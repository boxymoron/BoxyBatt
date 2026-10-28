package com.boxymoron.boxybatt.util

import android.app.AppOpsManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log
import com.boxymoron.boxybatt.model.AppBatteryUsage

/**
 * Collects per-app usage statistics from UsageStatsManager.
 * Estimates battery drain by correlating app active time with
 * the device's average current draw from battery history.
 *
 * Background time uses totalTimeForegroundServiceUsed (API 29+)
 * for accurate foreground-service duration (e.g., VPN services).
 */
class AppUsageCollector(private val context: Context) {

    companion object {
        private const val TAG = "AppUsageCollector"

        // Power weighting: foreground (screen on) draws more than background service
        // Background services (GPS, VPN, sync) use roughly 30-40% of foreground power
        private const val FG_WEIGHT = 1.0
        private const val BG_WEIGHT = 0.35

        @Volatile
        private var instance: AppUsageCollector? = null

        fun getInstance(context: Context): AppUsageCollector {
            return instance ?: synchronized(this) {
                instance ?: AppUsageCollector(context.applicationContext).also { instance = it }
            }
        }
    }

    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Get per-app usage data for the last [durationMs] milliseconds.
     * Uses battery history to estimate per-app drain in mAh.
     */
    fun getAppUsage(durationMs: Long = 24 * 60 * 60 * 1000L): List<AppBatteryUsage> {
        if (!hasUsageAccess()) {
            Log.w(TAG, "Usage access not granted")
            return emptyList()
        }

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE)
            as? UsageStatsManager ?: return emptyList()

        val endTime = System.currentTimeMillis()
        val startTime = endTime - durationMs

        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY, startTime, endTime
        )

        if (stats.isNullOrEmpty()) {
            Log.w(TAG, "No usage stats available")
            return emptyList()
        }


        // Aggregate by package name
        val aggregated = mutableMapOf<String, UsageStats>()
        for (stat in stats) {
            val existing = aggregated[stat.packageName]
            if (existing != null) {
                existing.add(stat)
            } else {
                aggregated[stat.packageName] = stat
            }
        }

        val pm = context.packageManager
        val apps = mutableListOf<AppBatteryUsage>()

        // Calculate total weighted time for proportional drain allocation
        var totalWeightedTime = 0.0
        data class AppTimeInfo(val fgTime: Long, val bgTime: Long)
        val appTimes = mutableMapOf<String, AppTimeInfo>()

        for ((pkgName, stat) in aggregated) {
            val fgTime = stat.totalTimeInForeground
            // Use the proper API for background/foreground-service time
            val bgTime = getBackgroundTime(stat)

            // Skip apps with negligible activity
            if (fgTime <= 0 && bgTime <= 0) continue

            val weighted = fgTime * FG_WEIGHT + bgTime * BG_WEIGHT
            totalWeightedTime += weighted
            appTimes[pkgName] = AppTimeInfo(fgTime, bgTime)
        }

        // Drain estimation: use level-based measurement
        val totalDrainMAh = getActualDrainMAh(durationMs)

        // Get per-app CPU time and RAM
        val perAppCpu = BatteryStatsParser.getPerAppCpuTimeMs(context)
        val perAppRam = BatteryStatsParser.getPerAppRamMB(context)

        val totalFgTime = aggregated.values.sumOf { it.totalTimeInForeground }

        for ((pkgName, stat) in aggregated) {
            val timeInfo = appTimes[pkgName] ?: continue

            try {
                val appInfo = pm.getApplicationInfo(pkgName, 0)
                val appName = pm.getApplicationLabel(appInfo).toString()
                val icon = try { pm.getApplicationIcon(pkgName) } catch (_: Exception) { null }
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val uid = appInfo.uid

                // Proportional drain based on weighted active time
                val weighted = timeInfo.fgTime * FG_WEIGHT + timeInfo.bgTime * BG_WEIGHT
                val drainMAh = if (totalWeightedTime > 0) {
                    totalDrainMAh * (weighted / totalWeightedTime)
                } else 0.0

                val pct = if (totalFgTime > 0) {
                    (timeInfo.fgTime * 100f / totalFgTime)
                } else 0f

                apps.add(
                    AppBatteryUsage(
                        packageName = pkgName,
                        appName = appName,
                        icon = icon,
                        uid = uid,
                        foregroundTimeMs = timeInfo.fgTime,
                        backgroundTimeMs = timeInfo.bgTime,
                        lastTimeUsed = stat.lastTimeUsed,
                        isSystemApp = isSystem,
                        usagePercent = pct,
                        estimatedDrainMAh = drainMAh,
                        cpuTimeMs = perAppCpu[pkgName] ?: 0L,
                        ramMB = perAppRam[pkgName] ?: 0L
                    )
                )
            } catch (_: PackageManager.NameNotFoundException) {
                // Package uninstalled
            }
        }

        return apps.sortedByDescending { it.estimatedDrainMAh }
    }

    /**
     * Get background time for an app using the best available API.
     *
     * API 29+ provides totalTimeForegroundServiceUsed which tracks actual
     * foreground-service running time (VPN services, media players, etc).
     * This is far more accurate than the old heuristic (lastTimeUsed - firstTimeStamp).
     *
     * For older APIs, we fall back to 0 (unknown) rather than an unreliable estimate.
     */
    private fun getBackgroundTime(stat: UsageStats): Long {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return stat.totalTimeForegroundServiceUsed
        }
        return 0L
    }

    /**
     * Calculate actual total device drain in mAh from our battery history.
     *
     * Uses measured level drop × estimated max capacity, scoped to the actual
     * time span we have data for (not the full query window).
     * This avoids the massive overestimate from extrapolating current × 24h.
     */
    private fun getActualDrainMAh(durationMs: Long): Double {
        val store = BatteryHistoryStore.getInstance(context)
        val since = System.currentTimeMillis() - durationMs
        val entries = store.getHistory(since)

        if (entries.size < 2) {
            // Not enough history — use a conservative live estimate
            val info = BatteryStatsCollector.collect(context)
            val currentMA = kotlin.math.abs(info.currentNow).toDouble()
            // Only estimate for 1 hour to be conservative
            return currentMA * 1.0
        }

        // Level drop across our actual data
        val firstLevel = entries.first().level
        val lastLevel = entries.last().level
        val levelDrop = firstLevel - lastLevel  // positive when discharging

        if (levelDrop <= 0) {
            // Battery was charged during this period; no meaningful drain
            return 0.0
        }

        // Estimate max capacity: we know current charge and percentage
        // Use the latest info for charge counter based estimate
        val info = BatteryStatsCollector.collect(context)
        val maxCapacityMAh = if (info.maxCapacityUAh > 0) {
            info.maxCapacityUAh / 1000.0
        } else if (info.chargeCounter > 0 && info.percentage > 5) {
            // Estimate: chargeCounter(µAh) / percentage * 100 → µAh → mAh
            (info.chargeCounter.toLong() * 100 / info.percentage) / 1000.0
        } else {
            // Fallback: typical smartphone capacity
            4000.0
        }

        // Actual drain = levelDrop% × maxCapacity
        return (levelDrop / 100.0) * maxCapacityMAh
    }
}
