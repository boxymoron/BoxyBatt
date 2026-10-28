package com.boxymoron.boxybatt.util

import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Collects hardware component states and usage metrics.
 * Reports current state (on/off/level) for power-hungry components.
 */
object HardwareStatsCollector {

    private const val TAG = "HardwareStats"

    data class HardwareSnapshot(
        val screenBrightness: Int,          // 0-255
        val screenBrightnessPercent: Int,    // 0-100
        val isAutoScreenBrightness: Boolean,
        val wifiState: WifiState,
        val wifiSignalPercent: Int,          // 0-100, -1 if off
        val wifiSSID: String,
        val bluetoothEnabled: Boolean,
        val bluetoothConnectedCount: Int,
        val gpsEnabled: Boolean,
        val locationMode: String,
        val mobileSignalLevel: Int,          // 0-4, -1 if unavailable
        val mobileNetworkType: String,
        val isDataRoaming: Boolean,
        val cpuUsagePercent: Double,          // 0-100
        val cpuCoreCount: Int,
        val ramTotalMB: Long,
        val ramUsedMB: Long,
        val ramAvailableMB: Long,
        val ramUsedPercent: Int,
        val networkType: String,             // WiFi, Cellular, None
        val isAirplaneMode: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        val screenBrightnessDisplay: String
            get() = if (isAutoScreenBrightness) "Auto ($screenBrightnessPercent%)" else "$screenBrightnessPercent%"

        val wifiDisplay: String
            get() = when (wifiState) {
                WifiState.CONNECTED -> "Connected ($wifiSignalPercent%)"
                WifiState.ON -> "On (not connected)"
                WifiState.OFF -> "Off"
            }

        val bluetoothDisplay: String
            get() = if (bluetoothEnabled) {
                if (bluetoothConnectedCount > 0) "On ($bluetoothConnectedCount devices)"
                else "On (idle)"
            } else "Off"

        val gpsDisplay: String
            get() = if (gpsEnabled) "On ($locationMode)" else "Off"

        val mobileSignalDisplay: String
            get() = if (mobileSignalLevel < 0) "N/A"
                    else "$mobileNetworkType (${mobileSignalLevel}/4)"

        val cpuDisplay: String
            get() = if (cpuUsagePercent > 0) String.format("%.0f%% (%d cores)", cpuUsagePercent, cpuCoreCount)
                    else "$cpuCoreCount cores"

        val ramDisplay: String
            get() {
                val usedGB = ramUsedMB / 1024.0
                val totalGB = ramTotalMB / 1024.0
                return String.format("%.1f / %.1f GB (%d%%)", usedGB, totalGB, ramUsedPercent)
            }

        /** Rough power impact estimate (1-5 stars) */
        val screenPowerImpact: Int
            get() = when {
                screenBrightnessPercent > 80 -> 5
                screenBrightnessPercent > 60 -> 4
                screenBrightnessPercent > 40 -> 3
                screenBrightnessPercent > 20 -> 2
                else -> 1
            }

        val wifiPowerImpact: Int
            get() = when (wifiState) {
                WifiState.CONNECTED -> 2
                WifiState.ON -> 1
                WifiState.OFF -> 0
            }

        val btPowerImpact: Int
            get() = when {
                !bluetoothEnabled -> 0
                bluetoothConnectedCount > 0 -> 2
                else -> 1
            }

        val gpsPowerImpact: Int
            get() = if (gpsEnabled) 3 else 0

        val cellPowerImpact: Int
            get() = when {
                isAirplaneMode -> 0
                mobileSignalLevel <= 1 -> 4  // poor signal = high power
                mobileSignalLevel <= 2 -> 3
                else -> 2
            }
    }

    enum class WifiState { OFF, ON, CONNECTED }

    // CPU tracking for delta calculation
    private var lastCpuIdle = 0L
    private var lastCpuTotal = 0L

    fun collect(context: Context): HardwareSnapshot {
        val ramInfo = getRamInfo(context)
        return HardwareSnapshot(
            screenBrightness = getScreenBrightness(context),
            screenBrightnessPercent = (getScreenBrightness(context) * 100 / 255),
            isAutoScreenBrightness = isAutoBrightness(context),
            wifiState = getWifiState(context),
            wifiSignalPercent = getWifiSignal(context),
            wifiSSID = getWifiSSID(context),
            bluetoothEnabled = isBluetoothOn(),
            bluetoothConnectedCount = getBluetoothConnectedCount(),
            gpsEnabled = isGpsEnabled(context),
            locationMode = getLocationMode(context),
            mobileSignalLevel = getMobileSignalLevel(context),
            mobileNetworkType = getMobileNetworkType(context),
            isDataRoaming = isRoaming(context),
            cpuUsagePercent = getCpuUsage(),
            cpuCoreCount = Runtime.getRuntime().availableProcessors(),
            ramTotalMB = ramInfo.first,
            ramUsedMB = ramInfo.second,
            ramAvailableMB = ramInfo.third,
            ramUsedPercent = if (ramInfo.first > 0) ((ramInfo.second * 100) / ramInfo.first).toInt() else 0,
            networkType = getActiveNetworkType(context),
            isAirplaneMode = isAirplaneModeOn(context)
        )
    }

    /** Returns Triple(totalMB, usedMB, availableMB) */
    private fun getRamInfo(context: Context): Triple<Long, Long, Long> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return Triple(0, 0, 0)
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val totalMB = memInfo.totalMem / (1024 * 1024)
        val availMB = memInfo.availMem / (1024 * 1024)
        val usedMB = totalMB - availMB
        return Triple(totalMB, usedMB, availMB)
    }

    private fun getScreenBrightness(context: Context): Int {
        return try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (_: Exception) { 128 }
    }

    private fun isAutoBrightness(context: Context): Boolean {
        return try {
            Settings.System.getInt(context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        } catch (_: Exception) { false }
    }

    private fun getWifiState(context: Context): WifiState {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return WifiState.OFF
        if (!wm.isWifiEnabled) return WifiState.OFF

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return WifiState.ON
        val net = cm.activeNetwork ?: return WifiState.ON
        val caps = cm.getNetworkCapabilities(net) ?: return WifiState.ON
        return if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) WifiState.CONNECTED
               else WifiState.ON
    }

    private fun getWifiSignal(context: Context): Int {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return -1
        if (!wm.isWifiEnabled) return -1
        @Suppress("DEPRECATION")
        val rssi = wm.connectionInfo?.rssi ?: return -1
        // Convert RSSI (-100 to -30) to percentage
        return WifiManager.calculateSignalLevel(rssi, 101).coerceIn(0, 100)
    }

    private fun getWifiSSID(context: Context): String {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return ""
        if (!wm.isWifiEnabled) return ""
        @Suppress("DEPRECATION")
        val ssid = wm.connectionInfo?.ssid ?: return ""
        return ssid.removePrefix("\"").removeSuffix("\"")
    }

    private fun isBluetoothOn(): Boolean {
        return try {
            BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
        } catch (_: Exception) { false }
    }

    private fun getBluetoothConnectedCount(): Int {
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return 0
            if (!adapter.isEnabled) return 0
            @Suppress("DEPRECATION")
            adapter.bondedDevices?.count() ?: 0
        } catch (_: SecurityException) { 0 }
        catch (_: Exception) { 0 }
    }

    private fun isGpsEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    private fun getLocationMode(context: Context): String {
        return try {
            val mode = Settings.Secure.getInt(context.contentResolver,
                Settings.Secure.LOCATION_MODE)
            when (mode) {
                Settings.Secure.LOCATION_MODE_OFF -> "Off"
                Settings.Secure.LOCATION_MODE_SENSORS_ONLY -> "GPS only"
                Settings.Secure.LOCATION_MODE_BATTERY_SAVING -> "Battery saving"
                Settings.Secure.LOCATION_MODE_HIGH_ACCURACY -> "High accuracy"
                else -> "Unknown"
            }
        } catch (_: Exception) { "Unknown" }
    }

    private fun getMobileSignalLevel(context: Context): Int {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                ?: return -1
            @Suppress("DEPRECATION")
            val info = tm.allCellInfo
            if (info.isNullOrEmpty()) return -1
            // Get the first registered cell's signal level (0-4)
            val cell = info.firstOrNull { it.isRegistered } ?: info.first()
            cell.cellSignalStrength.level
        } catch (_: SecurityException) { -1 }
        catch (_: Exception) { -1 }
    }

    private fun getMobileNetworkType(context: Context): String {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                ?: return "N/A"
            @Suppress("DEPRECATION")
            when (tm.networkType) {
                TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
                TelephonyManager.NETWORK_TYPE_NR -> "5G"
                TelephonyManager.NETWORK_TYPE_HSDPA,
                TelephonyManager.NETWORK_TYPE_HSUPA,
                TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_HSPAP -> "3G+"
                TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
                TelephonyManager.NETWORK_TYPE_EDGE -> "2G"
                TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
                else -> "Mobile"
            }
        } catch (_: SecurityException) { "N/A" }
        catch (_: Exception) { "N/A" }
    }

    private fun isRoaming(context: Context): Boolean {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            tm?.isNetworkRoaming == true
        } catch (_: Exception) { false }
    }

    private fun getCpuUsage(): Double {
        // Try /proc/stat first (fastest, most accurate)
        try {
            val lines = File("/proc/stat").readLines()
            val cpuLine = lines.firstOrNull { it.startsWith("cpu ") }
            if (cpuLine != null) {
                val parts = cpuLine.split("\\s+".toRegex()).drop(1).map { it.toLong() }
                if (parts.size >= 4) {
                    val idle = parts[3]
                    val total = parts.sum()
                    val diffIdle = idle - lastCpuIdle
                    val diffTotal = total - lastCpuTotal
                    lastCpuIdle = idle
                    lastCpuTotal = total
                    if (diffTotal > 0) {
                        return ((diffTotal - diffIdle) * 100.0 / diffTotal).coerceIn(0.0, 100.0)
                    }
                }
            }
        } catch (_: Exception) {
            // /proc/stat denied on Samsung — try 'top' fallback
        }

        // Fallback: parse 'top' output for CPU idle%
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("top", "-bn1", "-o", "%CPU"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var cpuPercent = 0.0
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                // Look for "%Cpu" or "Cpu" line: e.g. "%Cpu(s):  5.3 us,  2.1 sy, ..., 90.1 id"
                // Or Samsung top header: "  Mem:  7243744K total, ..."
                // Actually on Android, top output line 1 has idle:
                // "Tasks: N total, ... " then later lines have per-process
                // We can sum all process CPU from the per-line output
                if (l.trimStart().startsWith("PID") || l.isBlank()) continue
                // Parse per-process lines: PID USER ... %CPU ...
                val cols = l.trim().split("\\s+".toRegex())
                if (cols.size >= 9) {
                    val cpuVal = cols[8].toDoubleOrNull() // %CPU column (9th)
                    if (cpuVal != null) cpuPercent += cpuVal
                }
            }
            reader.close()
            process.waitFor()
            // Normalize by core count: top reports per-core, so 100% = 1 core
            val cores = Runtime.getRuntime().availableProcessors()
            (cpuPercent / cores).coerceIn(0.0, 100.0)
        } catch (e: Exception) {
            Log.d(TAG, "CPU top fallback failed: ${e.message}")
            0.0
        }
    }

    private fun getActiveNetworkType(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "None"
        val net = cm.activeNetwork ?: return "None"
        val caps = cm.getNetworkCapabilities(net) ?: return "None"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "Other"
        }
    }

    private fun isAirplaneModeOn(context: Context): Boolean {
        return Settings.Global.getInt(context.contentResolver,
            Settings.Global.AIRPLANE_MODE_ON, 0) != 0
    }
}
