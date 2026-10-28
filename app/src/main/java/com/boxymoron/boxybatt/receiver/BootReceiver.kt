package com.boxymoron.boxybatt.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.boxymoron.boxybatt.service.BatteryMonitorService

/**
 * Starts battery monitoring on device boot if auto-start is enabled.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("boxybatt_prefs", Context.MODE_PRIVATE)
            val autoStart = prefs.getBoolean("auto_start_on_boot", false)
            if (autoStart) {
                val serviceIntent = Intent(context, BatteryMonitorService::class.java).apply {
                    action = BatteryMonitorService.ACTION_START
                }
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }
}
