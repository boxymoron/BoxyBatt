package com.boxymoron.boxybatt.util

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Collects per-app CPU time and RAM usage using methods available
 * to unprivileged third-party apps.
 *
 * CPU: reads /proc/[pid]/stat for each running process (readable for
 * processes visible to us) and sums user+system ticks per package.
 *
 * RAM: uses ActivityManager.getProcessMemoryInfo() for PSS per process.
 *
 * Note: dumpsys batterystats is NOT accessible from app process context
 * even with BATTERY_STATS permission (requires shell UID).
 */
object BatteryStatsParser {

    private const val TAG = "BatteryStatsParser"
    private val clockTicksPerSec = getClockTicks()

    /**
     * Per-app CPU and RAM result.
     */
    data class AppResourceInfo(
        val packageName: String,
        val cpuTimeMs: Long,    // total CPU time (user + system) in ms
        val ramPssMB: Long      // PSS RAM in MB
    )

    /**
     * Get per-app CPU time by reading /proc/[pid]/stat for all running processes.
     * Returns map of packageName -> total CPU time in ms.
     */
    fun getPerAppCpuTimeMs(context: Context): Map<String, Long> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return emptyMap()
        val result = mutableMapOf<String, Long>()

        try {
            @Suppress("DEPRECATION")
            val processes = am.runningAppProcesses ?: return emptyMap()

            for (proc in processes) {
                val cpuMs = readProcessCpuTimeMs(proc.pid)
                if (cpuMs > 0 && proc.pkgList != null) {
                    val perPkg = cpuMs / proc.pkgList.size
                    for (pkg in proc.pkgList) {
                        result[pkg] = (result[pkg] ?: 0L) + perPkg
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read process CPU: ${e.message}")
        }

        return result
    }

    /**
     * Get RAM (PSS in MB) for running apps.
     * Returns map of packageName -> PSS in MB.
     */
    fun getPerAppRamMB(context: Context): Map<String, Long> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return emptyMap()
        val result = mutableMapOf<String, Long>()

        try {
            @Suppress("DEPRECATION")
            val processes = am.runningAppProcesses ?: return emptyMap()
            val pids = processes.map { it.pid }.toIntArray()
            val memInfos = am.getProcessMemoryInfo(pids)

            for (i in processes.indices) {
                if (i >= memInfos.size) break
                val proc = processes[i]
                val pss = memInfos[i].totalPss / 1024L  // KB -> MB
                for (pkg in proc.pkgList ?: emptyArray()) {
                    result[pkg] = (result[pkg] ?: 0L) + pss
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get per-app RAM: ${e.message}")
        }

        return result
    }

    /**
     * Read CPU time in ms for a given PID from /proc/[pid]/stat.
     *
     * /proc/[pid]/stat format (space separated):
     *   field 14 (0-indexed 13): utime - user mode ticks
     *   field 15 (0-indexed 14): stime - kernel mode ticks
     *   field 16 (0-indexed 15): cutime - children user ticks
     *   field 17 (0-indexed 16): cstime - children kernel ticks
     *
     * Returns total CPU time in milliseconds, or 0 if unreadable.
     */
    private fun readProcessCpuTimeMs(pid: Int): Long {
        return try {
            val stat = File("/proc/$pid/stat").readText()
            // The comm field (field 2) can contain spaces and parens,
            // so find the closing paren and parse from there
            val closeParen = stat.lastIndexOf(')')
            if (closeParen < 0) return 0L
            val fields = stat.substring(closeParen + 2).split(' ')
            // fields[0] = state, fields[1..] = ppid, pgrp, session, ...
            // utime = fields[11], stime = fields[12] (0-indexed from after ')')
            if (fields.size < 13) return 0L
            val utime = fields[11].toLongOrNull() ?: 0L
            val stime = fields[12].toLongOrNull() ?: 0L
            // Convert ticks to milliseconds
            ((utime + stime) * 1000L) / clockTicksPerSec
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Get clock ticks per second (usually 100 on Android/Linux).
     */
    private fun getClockTicks(): Long {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("getconf", "CLK_TCK"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val value = reader.readLine()?.trim()?.toLongOrNull() ?: 100L
            reader.close()
            process.waitFor()
            value
        } catch (_: Exception) {
            100L // Default on most Linux/Android systems
        }
    }

    /**
     * Check if we have BATTERY_STATS permission.
     * Note: even with this permission, dumpsys is not accessible from app context.
     * This is kept for informational display in Advanced Settings.
     */
    fun hasBatteryStatsPermission(context: Context): Boolean {
        return context.checkCallingOrSelfPermission("android.permission.BATTERY_STATS") ==
            PackageManager.PERMISSION_GRANTED
    }
}
