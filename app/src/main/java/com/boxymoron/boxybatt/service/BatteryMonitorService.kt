package com.boxymoron.boxybatt.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.boxymoron.boxybatt.MainActivity
import com.boxymoron.boxybatt.R
import com.boxymoron.boxybatt.util.BatteryHistoryStore
import com.boxymoron.boxybatt.util.BatteryStatsCollector
import kotlinx.coroutines.*

/**
 * Foreground service that periodically logs battery data to SQLite.
 * Runs in the background with a persistent notification showing current battery level.
 */
class BatteryMonitorService : Service() {

    companion object {
        const val ACTION_START = "com.boxymoron.boxybatt.START"
        const val ACTION_STOP = "com.boxymoron.boxybatt.STOP"
        const val ACTION_STATE_CHANGED = "com.boxymoron.boxybatt.STATE_CHANGED"

        private const val TAG = "BatteryMonitorService"
        private const val CHANNEL_ID = "boxybatt_monitor"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS_NAME = "boxybatt_prefs"
        private const val KEY_POLL_INTERVAL_MS = "poll_interval_ms"
        private const val DEFAULT_POLL_INTERVAL_MS = 5_000L  // 5 seconds

        @Volatile
        var isRunning = false
            private set

        var instance: BatteryMonitorService? = null
            private set
    }

    private var serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var logJob: Job? = null
    private lateinit var historyStore: BatteryHistoryStore
    @Volatile private var pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        historyStore = BatteryHistoryStore.getInstance(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMonitoring()
                return START_NOT_STICKY
            }
            else -> {
                startMonitoring()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMonitoring()
        instance = null
    }

    private fun startMonitoring() {
        // Load interval from prefs
        pollIntervalMs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getLong(KEY_POLL_INTERVAL_MS, DEFAULT_POLL_INTERVAL_MS)

        isRunning = true
        startForeground(NOTIFICATION_ID, buildNotification("Starting..."))
        broadcastState()

        // Log immediately on start
        logBatterySnapshot()

        // Start periodic logging
        startLogLoop()

        Log.d(TAG, "Battery monitoring started (interval: ${pollIntervalMs}ms)")
    }

    private fun startLogLoop() {
        logJob?.cancel()
        logJob = serviceScope.launch {
            while (isActive) {
                delay(pollIntervalMs)
                logBatterySnapshot()
                updateNotification()
                // Prune old data weekly
                historyStore.pruneOldEntries()
            }
        }
    }

    /**
     * Called from AdvancedSettingsActivity to update interval without restarting service.
     */
    fun updatePollInterval(newIntervalMs: Long) {
        pollIntervalMs = newIntervalMs
        if (isRunning) {
            startLogLoop()  // Restart the loop with new interval
            Log.d(TAG, "Poll interval updated to ${newIntervalMs}ms")
        }
    }

    private fun stopMonitoring() {
        isRunning = false
        logJob?.cancel()
        logJob = null
        broadcastState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "Battery monitoring stopped")
    }

    private fun logBatterySnapshot() {
        val info = BatteryStatsCollector.collect(this)
        historyStore.logBattery(info)
        Log.d(TAG, "Logged: ${info.percentage}% ${info.temperatureDisplay} ${info.statusDisplay}")
    }

    private fun updateNotification() {
        val info = BatteryStatsCollector.collect(this)
        val notification = buildNotification(
            "${info.percentage}% · ${info.temperatureDisplay} · ${info.statusDisplay}"
        )
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_battery_notification)
            .setContentTitle("BoxyBatt Monitor")
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun broadcastState() {
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }
}
