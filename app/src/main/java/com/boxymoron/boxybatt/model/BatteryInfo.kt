package com.boxymoron.boxybatt.model

/**
 * Represents the current battery state read from BatteryManager.
 */
data class BatteryInfo(
    val level: Int = -1,
    val scale: Int = 100,
    val temperature: Float = 0f,       // in °C
    val voltage: Float = 0f,           // in V
    val health: Int = 0,
    val status: Int = 0,
    val plugged: Int = 0,
    val technology: String = "Unknown",
    val currentNow: Int = 0,           // in mA (damped, always milliamps)
    val energyCounter: Long = -1L,     // in nWh
    val chargeCounter: Int = -1,       // in µAh
    val maxCapacityUAh: Long = -1L,    // estimated max capacity in µAh
    val estimatedMinutesLeft: Int = -1, // estimated battery life remaining
    val timestamp: Long = System.currentTimeMillis()
) {
    val percentage: Int
        get() = if (scale > 0) (level * 100) / scale else level

    val temperatureDisplay: String
        get() = String.format("%.1f°C", temperature)

    val voltageDisplay: String
        get() = String.format("%.2fV", voltage)

    val healthDisplay: String
        get() = when (health) {
            android.os.BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            android.os.BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            android.os.BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            android.os.BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over V"
            android.os.BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            android.os.BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
            else -> "Unknown"
        }

    val statusDisplay: String
        get() = when (status) {
            android.os.BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
            android.os.BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
            android.os.BatteryManager.BATTERY_STATUS_FULL -> "FULL"
            android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT CHARGING"
            else -> "UNKNOWN"
        }

    val pluggedDisplay: String
        get() = when (plugged) {
            android.os.BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            android.os.BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> "Unplugged"
        }

    val isCharging: Boolean
        get() = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                status == android.os.BatteryManager.BATTERY_STATUS_FULL

    val currentDisplay: String
        get() {
            if (currentNow == 0) return "N/A"
            return String.format("%d mA", kotlin.math.abs(currentNow))
        }

    val capacityDisplay: String
        get() {
            if (chargeCounter < 0) return "N/A"
            val mAh = chargeCounter / 1000.0
            return String.format("%.0f mAh", mAh)
        }

    val maxCapacityDisplay: String
        get() {
            if (maxCapacityUAh <= 0) return "N/A"
            val mAh = maxCapacityUAh / 1000.0
            return String.format("%.0f mAh", mAh)
        }

    val energyDisplay: String
        get() {
            if (energyCounter < 0) return "N/A"
            val mWh = energyCounter / 1_000_000.0
            return String.format("%.1f mWh", mWh)
        }

    val estimatedTimeDisplay: String
        get() {
            if (estimatedMinutesLeft < 0) return "N/A"
            if (isCharging) return "Charging"
            val hours = estimatedMinutesLeft / 60
            val mins = estimatedMinutesLeft % 60
            return when {
                hours > 24 -> "${hours / 24}d ${hours % 24}h"
                hours > 0 -> "${hours}h ${mins}m"
                else -> "${mins}m"
            }
        }

    /** Power in watts: P = V × I. currentNow is in mA, voltage is in V. */
    val powerWatts: Double
        get() {
            if (currentNow == 0) return 0.0
            return voltage * kotlin.math.abs(currentNow) / 1000.0  // V * A = W
        }

    val powerDisplay: String
        get() {
            if (currentNow == 0 || voltage <= 0) return "N/A"
            return String.format("%.2f W", powerWatts)
        }
}
