package com.boxymoron.boxybatt.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.boxymoron.boxybatt.model.BatteryInfo
import java.io.File

/**
 * Collects battery data from multiple sources with cascading fallback:
 * 1. Standard BatteryManager API
 * 2. OEM-specific intent extras (Samsung: current_avg, cc)
 * 3. Sysfs filesystem paths
 *
 * Samsung devices (Galaxy S series, etc.) expose current and charge counter
 * through custom extras on ACTION_BATTERY_CHANGED rather than the standard API.
 */
object BatteryStatsCollector {

    private const val TAG = "BatteryStatsCollector"

    // Samsung & common OEM custom intent extra keys
    private const val EXTRA_CURRENT_AVG = "current_avg"      // Samsung: avg current in mA
    private const val EXTRA_CURRENT_NOW = "current_now"       // Some OEMs
    private const val EXTRA_CHARGE_COUNTER = "charge_counter" // Samsung: µAh
    private const val EXTRA_CC = "cc"                         // Samsung shorthand: µAh
    private const val EXTRA_BATT_CURRENT_EVENT = "current_event" // Samsung

    // Sysfs fallback paths
    private val CURRENT_NOW_PATHS = listOf(
        "/sys/class/power_supply/battery/current_now",
        "/sys/class/power_supply/battery/current_avg",
        "/sys/class/power_supply/battery/BatteryAverageCurrent",
        "/sys/class/power_supply/bms/current_now",
        "/sys/class/power_supply/max77705-fuelgauge/current_now",
        "/sys/class/power_supply/main/current_now",
        "/sys/class/power_supply/Battery/current_now"
    )

    private val CHARGE_COUNTER_PATHS = listOf(
        "/sys/class/power_supply/battery/charge_counter",
        "/sys/class/power_supply/battery/charge_full",
        "/sys/class/power_supply/bms/charge_counter",
        "/sys/class/power_supply/max77705-fuelgauge/charge_counter"
    )

    @Volatile private var cachedCurrentPath: String? = null
    @Volatile private var cachedChargePath: String? = null
    @Volatile private var sysfsProbed = false

    // Estimate max capacity from first good charge_counter / level reading
    @Volatile private var estimatedMaxCapacityUAh: Long = -1

    // Exponentially weighted moving average (EWMA) damping for current (in mA)
    // Keeps last 10 raw samples; newer samples weighted more heavily
    private val currentSamples = mutableListOf<Int>()  // stores mA values
    private const val MAX_SAMPLES = 10
    private const val DECAY_FACTOR = 0.8  // weight multiplier per older sample

    fun collect(context: Context): BatteryInfo {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

        // Sticky intent
        val intent: Intent? = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        // === Standard intent extras ===
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val tempRaw = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val voltRaw = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val health = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0) ?: 0
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, 0) ?: 0
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val technology = intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "Unknown"

        // === Current: cascade through sources, normalize to mA ===
        var currentMA = 0  // in mA (milliamps)

        // 1. Try BatteryManager API
        val apiCurrent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (isValidReading(apiCurrent)) {
            // Samsung returns mA directly (small values like -107, -302)
            // Other devices return µA (large values like -107000, -302000)
            // Heuristic: if abs(value) < 10000, it's already in mA
            currentMA = normalizeToMA(apiCurrent)
            Log.d(TAG, "Current from API: raw=$apiCurrent → ${currentMA} mA")
        }

        // 2. Try OEM intent extras (Samsung: current_avg in mA)
        if (currentMA == 0 && intent != null) {
            val intentCurrent = getIntentCurrent(intent)
            if (intentCurrent != 0) {
                // Samsung's current_avg is in mA
                currentMA = intentCurrent
                Log.d(TAG, "Current from intent extra: ${currentMA} mA")
            }
        }

        // 3. Try sysfs fallback
        if (currentMA == 0) {
            if (!sysfsProbed) probeSysfsPaths()
            val sysfsCurrent = readSysfsInt(cachedCurrentPath)
            if (sysfsCurrent != null && sysfsCurrent != 0) {
                currentMA = normalizeToMA(sysfsCurrent)
                Log.d(TAG, "Current from sysfs: raw=$sysfsCurrent → ${currentMA} mA")
            }
        }

        // === Apply EWMA damping to current (in mA) ===
        val dampedCurrentMA = if (currentMA != 0) {
            dampCurrentReading(currentMA)
        } else {
            // Don't add zeroes to the buffer; return last damped if available
            if (currentSamples.isNotEmpty()) computeEWMA() else 0
        }

        // === Charge counter: cascade through sources ===
        var chargeCounter = -1  // in µAh

        // 1. Try BatteryManager API
        val apiCharge = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        if (isValidReading(apiCharge) && apiCharge > 0) {
            chargeCounter = apiCharge
            Log.d(TAG, "Charge counter from API: $apiCharge µAh")
        }

        // 2. Try OEM intent extras (Samsung: "cc" in µAh)
        if (chargeCounter <= 0 && intent != null) {
            val intentCC = getIntentChargeCounter(intent)
            if (intentCC > 0) {
                chargeCounter = intentCC
                Log.d(TAG, "Charge counter from intent extra: $intentCC µAh")
            }
        }

        // 3. Try sysfs fallback
        if (chargeCounter <= 0) {
            if (!sysfsProbed) probeSysfsPaths()
            val sysfsCharge = readSysfsInt(cachedChargePath)
            if (sysfsCharge != null && sysfsCharge > 0) {
                chargeCounter = sysfsCharge
                Log.d(TAG, "Charge counter from sysfs: $sysfsCharge µAh")
            }
        }

        // === Estimate max capacity ===
        val percentage = if (scale > 0) (level * 100) / scale else level
        if (chargeCounter > 0 && percentage > 5) {
            val estimated = (chargeCounter.toLong() * 100) / percentage
            if (estimatedMaxCapacityUAh < 0 || percentage > 90) {
                // Update estimate, prefer readings at high charge levels
                estimatedMaxCapacityUAh = estimated
            }
        }

        // === Energy counter ===
        var energyCounter = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
        if (energyCounter == Long.MIN_VALUE || energyCounter <= 0) {
            // Estimate energy from voltage × charge: E = V × Q
            // chargeCounter is µAh, voltage is mV → energy in nWh = mV × µAh
            if (chargeCounter > 0 && voltRaw > 0) {
                energyCounter = voltRaw.toLong() * chargeCounter.toLong()  // nWh
                Log.d(TAG, "Energy estimated from V×Q: $energyCounter nWh")
            } else {
                energyCounter = -1L
            }
        }

        // === Estimated battery life remaining (use damped current in mA) ===
        // chargeCounter is in µAh, convert to mAh for consistent units
        val chargeMAh = chargeCounter / 1000.0
        val estimatedMinutesLeft = calculateRemainingMinutes(chargeMAh, dampedCurrentMA)

        return BatteryInfo(
            level = level,
            scale = scale,
            temperature = tempRaw / 10f,
            voltage = voltRaw / 1000f,
            health = health,
            status = status,
            plugged = plugged,
            technology = technology,
            currentNow = dampedCurrentMA,
            energyCounter = energyCounter,
            chargeCounter = chargeCounter,
            maxCapacityUAh = estimatedMaxCapacityUAh,
            estimatedMinutesLeft = estimatedMinutesLeft,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Try to read current from Samsung/OEM-specific intent extras.
     * Samsung uses "current_avg" in mA (negative = discharging).
     */
    private fun getIntentCurrent(intent: Intent): Int {
        // Samsung "current_avg" — in mA
        var current = intent.getIntExtra(EXTRA_CURRENT_AVG, 0)
        if (current != 0) return current

        // Some OEMs use "current_now"
        current = intent.getIntExtra(EXTRA_CURRENT_NOW, 0)
        if (current != 0) return current

        return 0
    }

    /**
     * Try to read charge counter from Samsung/OEM-specific intent extras.
     * Samsung uses "cc" — in µAh.
     */
    private fun getIntentChargeCounter(intent: Intent): Int {
        // Samsung "cc" shorthand
        var cc = intent.getIntExtra(EXTRA_CC, 0)
        if (cc > 0) return cc

        // Some OEMs explicit charge_counter
        cc = intent.getIntExtra(EXTRA_CHARGE_COUNTER, 0)
        if (cc > 0) return cc

        return 0
    }

    /**
     * Estimate remaining minutes from charge (mAh) and current draw (mA).
     * Both must be in the same unit scale (millis).
     */
    private fun calculateRemainingMinutes(chargeMAh: Double, currentMA: Int): Int {
        if (chargeMAh <= 0 || currentMA == 0) return -1

        // current is negative when discharging
        val drainMA = kotlin.math.abs(currentMA)
        if (drainMA == 0) return -1

        // remaining_hours = mAh / mA = hours
        val hoursLeft = chargeMAh / drainMA.toDouble()
        return (hoursLeft * 60).toInt()
    }

    /**
     * Normalize a raw current value to milliamps.
     * - Samsung returns mA directly via the API (small values like -107, -302)
     * - Other devices return µA (large values like -107000, -302000)
     * Heuristic: if abs(value) < 10000, it's already mA; otherwise convert µA→mA.
     */
    private fun normalizeToMA(rawValue: Int): Int {
        val abs = kotlin.math.abs(rawValue)
        return if (abs < 10_000) {
            // Already in mA (Samsung, some Qualcomm)
            rawValue
        } else {
            // In µA, convert to mA
            rawValue / 1000
        }
    }

    private fun isValidReading(value: Int): Boolean {
        return value != 0 && value != Int.MIN_VALUE
    }

    /**
     * Add a raw sample to the EWMA buffer and return the damped value.
     */
    private fun dampCurrentReading(rawUA: Int): Int {
        synchronized(currentSamples) {
            currentSamples.add(rawUA)
            if (currentSamples.size > MAX_SAMPLES) {
                currentSamples.removeAt(0)
            }
            return computeEWMA()
        }
    }

    /**
     * Compute exponentially weighted moving average.
     * Newest sample has weight 1.0, each older sample is multiplied by DECAY_FACTOR.
     * Example with 5 samples: weights [0.41, 0.51, 0.64, 0.8, 1.0]
     */
    private fun computeEWMA(): Int {
        if (currentSamples.isEmpty()) return 0
        var weightedSum = 0.0
        var weightTotal = 0.0
        val n = currentSamples.size
        for (i in 0 until n) {
            // i=0 is oldest, i=n-1 is newest
            val age = n - 1 - i
            val weight = Math.pow(DECAY_FACTOR, age.toDouble())
            weightedSum += currentSamples[i] * weight
            weightTotal += weight
        }
        return (weightedSum / weightTotal).toInt()
    }

    private fun probeSysfsPaths() {
        cachedCurrentPath = CURRENT_NOW_PATHS.firstOrNull { path ->
            try { File(path).let { it.exists() && it.canRead() } } catch (_: Exception) { false }
        }
        cachedChargePath = CHARGE_COUNTER_PATHS.firstOrNull { path ->
            try { File(path).let { it.exists() && it.canRead() } } catch (_: Exception) { false }
        }
        sysfsProbed = true
        Log.d(TAG, "Sysfs probed — current: $cachedCurrentPath, charge: $cachedChargePath")
    }

    private fun readSysfsInt(path: String?): Int? {
        if (path == null) return null
        return try { File(path).readText().trim().toIntOrNull() }
        catch (e: Exception) { Log.w(TAG, "Sysfs read failed: $path"); null }
    }

    // ===== Public API for Advanced Settings screen =====

    /**
     * Data class representing a single data source probe result.
     */
    data class DataSourceInfo(
        val name: String,
        val category: String,      // "current", "charge", "energy"
        val source: String,        // "api", "intent", "sysfs"
        val path: String,          // extra key or file path
        val rawValue: String,      // raw value as string
        val available: Boolean,
        val unit: String
    )

    /**
     * Probe ALL available data sources and return results.
     * Used by Advanced Settings screen to show what's available on this device.
     */
    fun probeAllSources(context: Context): List<DataSourceInfo> {
        val results = mutableListOf<DataSourceInfo>()
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val intent: Intent? = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        // --- Current sources ---

        // 1. BatteryManager API
        val apiCurrent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        results.add(DataSourceInfo(
            name = "BatteryManager API",
            category = "current",
            source = "api",
            path = "BATTERY_PROPERTY_CURRENT_NOW",
            rawValue = if (isValidReading(apiCurrent)) "$apiCurrent µA" else "unavailable (${apiCurrent})",
            available = isValidReading(apiCurrent),
            unit = "µA"
        ))

        // 2. Intent extras
        if (intent != null) {
            for (key in listOf(EXTRA_CURRENT_AVG, EXTRA_CURRENT_NOW, "current_max")) {
                val value = intent.getIntExtra(key, 0)
                results.add(DataSourceInfo(
                    name = "Intent: $key",
                    category = "current",
                    source = "intent",
                    path = key,
                    rawValue = if (value != 0) "$value mA" else "unavailable",
                    available = value != 0,
                    unit = "mA"
                ))
            }
        }

        // 3. Sysfs paths
        for (path in CURRENT_NOW_PATHS) {
            val file = File(path)
            val exists = try { file.exists() } catch (_: Exception) { false }
            val readable = try { file.canRead() } catch (_: Exception) { false }
            val value = if (exists && readable) readSysfsInt(path) else null
            results.add(DataSourceInfo(
                name = "sysfs: ${path.substringAfterLast("/")}",
                category = "current",
                source = "sysfs",
                path = path,
                rawValue = when {
                    value != null -> "$value µA"
                    exists && !readable -> "permission denied"
                    !exists -> "not found"
                    else -> "unavailable"
                },
                available = value != null && value != 0,
                unit = "µA"
            ))
        }

        // --- Charge counter sources ---

        val apiCharge = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        results.add(DataSourceInfo(
            name = "BatteryManager API",
            category = "charge",
            source = "api",
            path = "BATTERY_PROPERTY_CHARGE_COUNTER",
            rawValue = if (isValidReading(apiCharge) && apiCharge > 0) "$apiCharge µAh" else "unavailable (${apiCharge})",
            available = isValidReading(apiCharge) && apiCharge > 0,
            unit = "µAh"
        ))

        if (intent != null) {
            for (key in listOf(EXTRA_CC, EXTRA_CHARGE_COUNTER)) {
                val value = intent.getIntExtra(key, 0)
                results.add(DataSourceInfo(
                    name = "Intent: $key",
                    category = "charge",
                    source = "intent",
                    path = key,
                    rawValue = if (value > 0) "$value µAh" else "unavailable",
                    available = value > 0,
                    unit = "µAh"
                ))
            }
        }

        for (path in CHARGE_COUNTER_PATHS) {
            val file = File(path)
            val exists = try { file.exists() } catch (_: Exception) { false }
            val readable = try { file.canRead() } catch (_: Exception) { false }
            val value = if (exists && readable) readSysfsInt(path) else null
            results.add(DataSourceInfo(
                name = "sysfs: ${path.substringAfterLast("/")}",
                category = "charge",
                source = "sysfs",
                path = path,
                rawValue = when {
                    value != null -> "$value µAh"
                    exists && !readable -> "permission denied"
                    !exists -> "not found"
                    else -> "unavailable"
                },
                available = value != null && value != 0,
                unit = "µAh"
            ))
        }

        // --- Energy counter ---
        val apiEnergy = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
        results.add(DataSourceInfo(
            name = "BatteryManager API",
            category = "energy",
            source = "api",
            path = "BATTERY_PROPERTY_ENERGY_COUNTER",
            rawValue = if (apiEnergy != Long.MIN_VALUE && apiEnergy > 0) "$apiEnergy nWh" else "unavailable",
            available = apiEnergy != Long.MIN_VALUE && apiEnergy > 0,
            unit = "nWh"
        ))

        // --- Power supply info ---
        val psDir = File("/sys/class/power_supply/")
        if (psDir.exists()) {
            val providers = try { psDir.listFiles()?.map { it.name } ?: emptyList() } catch (_: Exception) { emptyList() }
            results.add(DataSourceInfo(
                name = "Power supply providers",
                category = "info",
                source = "sysfs",
                path = "/sys/class/power_supply/",
                rawValue = providers.joinToString(", "),
                available = providers.isNotEmpty(),
                unit = ""
            ))
        }

        return results
    }
}
