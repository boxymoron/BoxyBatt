package com.boxymoron.boxybatt

import android.Manifest
import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.boxymoron.boxybatt.service.BatteryMonitorService
import com.boxymoron.boxybatt.util.BatteryStatsCollector
import com.boxymoron.boxybatt.util.HardwareStatsCollector
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatusDetail: TextView
    private lateinit var tvBatteryLevel: TextView
    private lateinit var tvChargingStatus: TextView
    private lateinit var tvHealth: TextView
    private lateinit var tvTemperature: TextView
    private lateinit var tvVoltage: TextView
    private lateinit var tvPlugType: TextView
    private lateinit var tvTechnology: TextView
    private lateinit var tvCurrent: TextView
    private lateinit var tvCapacity: TextView
    private lateinit var tvEnergy: TextView
    private lateinit var tvPower: TextView
    private lateinit var tvMaxCapacity: TextView
    private lateinit var tvEstTime: TextView
    private lateinit var switchService: SwitchCompat
    private lateinit var permissionNotification: LinearLayout
    private lateinit var permissionUsage: LinearLayout
    private lateinit var ivCheckNotification: ImageView
    private lateinit var ivCheckUsage: ImageView
    private lateinit var tvHwScreen: TextView
    private lateinit var tvHwWifi: TextView
    private lateinit var tvHwBluetooth: TextView
    private lateinit var tvHwGps: TextView
    private lateinit var tvHwMobile: TextView
    private lateinit var tvHwCpu: TextView
    private lateinit var tvHwRam: TextView

    private var isServiceActive = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var refreshJob: Job? = null

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            isServiceActive = BatteryMonitorService.isRunning
            updateUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupClickListeners()

        isServiceActive = BatteryMonitorService.isRunning
        updateUI()

        registerReceiver(
            stateReceiver,
            IntentFilter(BatteryMonitorService.ACTION_STATE_CHANGED),
            RECEIVER_NOT_EXPORTED
        )
    }

    override fun onResume() {
        super.onResume()
        isServiceActive = BatteryMonitorService.isRunning
        updateUI()
        updatePermissionStatus()
        startAutoRefresh()
    }

    override fun onPause() {
        super.onPause()
        refreshJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        try { unregisterReceiver(stateReceiver) } catch (_: Exception) {}
    }

    private fun initViews() {
        tvStatusDetail = findViewById(R.id.tv_status_detail)
        tvBatteryLevel = findViewById(R.id.tv_battery_level)
        tvChargingStatus = findViewById(R.id.tv_charging_status)
        tvHealth = findViewById(R.id.tv_health)
        tvTemperature = findViewById(R.id.tv_temperature)
        tvVoltage = findViewById(R.id.tv_voltage)
        tvPlugType = findViewById(R.id.tv_plug_type)
        tvTechnology = findViewById(R.id.tv_technology)
        tvCurrent = findViewById(R.id.tv_current)
        tvCapacity = findViewById(R.id.tv_capacity)
        tvEnergy = findViewById(R.id.tv_energy)
        tvPower = findViewById(R.id.tv_power)
        tvMaxCapacity = findViewById(R.id.tv_max_capacity)
        tvEstTime = findViewById(R.id.tv_est_time)
        switchService = findViewById(R.id.switch_service)
        permissionNotification = findViewById(R.id.permission_notification)
        permissionUsage = findViewById(R.id.permission_usage)
        ivCheckNotification = findViewById(R.id.iv_check_notification)
        ivCheckUsage = findViewById(R.id.iv_check_usage)
        tvHwScreen = findViewById(R.id.tv_hw_screen)
        tvHwWifi = findViewById(R.id.tv_hw_wifi)
        tvHwBluetooth = findViewById(R.id.tv_hw_bluetooth)
        tvHwGps = findViewById(R.id.tv_hw_gps)
        tvHwMobile = findViewById(R.id.tv_hw_mobile)
        tvHwCpu = findViewById(R.id.tv_hw_cpu)
        tvHwRam = findViewById(R.id.tv_hw_ram)
    }

    private fun setupClickListeners() {
        switchService.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !isServiceActive) {
                startMonitorService()
            } else if (!isChecked && isServiceActive) {
                stopMonitorService()
            }
        }

        permissionNotification.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        1001
                    )
                }
            }
        }

        permissionUsage.setOnClickListener {
            if (!hasUsageAccess()) {
                startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }

        findViewById<android.widget.Button>(R.id.btn_app_usage).setOnClickListener {
            startActivity(Intent(this, AppUsageActivity::class.java))
        }

        findViewById<android.widget.Button>(R.id.btn_history).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        findViewById<android.widget.Button>(R.id.btn_about).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        findViewById<android.widget.Button>(R.id.btn_advanced).setOnClickListener {
            startActivity(Intent(this, AdvancedSettingsActivity::class.java))
        }
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun startMonitorService() {
        val intent = Intent(this, BatteryMonitorService::class.java).apply {
            action = BatteryMonitorService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
        getSharedPreferences("boxybatt_prefs", MODE_PRIVATE)
            .edit().putBoolean("auto_start_on_boot", true).apply()
        isServiceActive = true
        updateUI()
    }

    private fun stopMonitorService() {
        val intent = Intent(this, BatteryMonitorService::class.java).apply {
            action = BatteryMonitorService.ACTION_STOP
        }
        startService(intent)
        getSharedPreferences("boxybatt_prefs", MODE_PRIVATE)
            .edit().putBoolean("auto_start_on_boot", false).apply()
        isServiceActive = false
        updateUI()
    }

    private fun startAutoRefresh() {
        refreshJob = scope.launch {
            while (isActive) {
                updateBatteryData()
                delay(3000)  // Refresh every 3s
            }
        }
    }

    private fun updateBatteryData() {
        val info = BatteryStatsCollector.collect(this)

        // Level
        tvBatteryLevel.text = "${info.percentage}%"
        val levelColor = when {
            info.percentage > 60 -> R.color.battery_high
            info.percentage > 20 -> R.color.battery_medium
            info.percentage > 10 -> R.color.battery_low
            else -> R.color.battery_critical
        }
        tvBatteryLevel.setTextColor(ContextCompat.getColor(this, levelColor))

        // Charging status
        tvChargingStatus.text = if (info.isCharging) {
            "⚡ ${info.statusDisplay} · ${info.pluggedDisplay}"
        } else {
            "🔋 ${info.statusDisplay}"
        }
        tvChargingStatus.setTextColor(ContextCompat.getColor(this,
            if (info.isCharging) R.color.accent_green else R.color.text_secondary
        ))

        // Health
        tvHealth.text = info.healthDisplay
        tvHealth.setTextColor(ContextCompat.getColor(this,
            if (info.healthDisplay == "Good") R.color.accent_green else R.color.accent_orange
        ))

        // Temperature
        tvTemperature.text = info.temperatureDisplay
        val tempColor = when {
            info.temperature > 45 -> R.color.accent_red
            info.temperature > 38 -> R.color.accent_orange
            else -> R.color.accent_orange
        }
        tvTemperature.setTextColor(ContextCompat.getColor(this, tempColor))

        // Voltage
        tvVoltage.text = info.voltageDisplay

        // Details
        tvPlugType.text = info.pluggedDisplay
        tvTechnology.text = info.technology
        tvCurrent.text = info.currentDisplay
        tvCapacity.text = info.capacityDisplay
        tvEnergy.text = info.energyDisplay
        tvPower.text = info.powerDisplay
        tvMaxCapacity.text = info.maxCapacityDisplay
        tvEstTime.text = info.estimatedTimeDisplay

        // Hardware metrics
        val hw = HardwareStatsCollector.collect(this)
        tvHwScreen.text = hw.screenBrightnessDisplay
        tvHwWifi.text = hw.wifiDisplay
        tvHwBluetooth.text = hw.bluetoothDisplay
        tvHwGps.text = hw.gpsDisplay
        tvHwMobile.text = hw.mobileSignalDisplay
        tvHwCpu.text = hw.cpuDisplay
        tvHwRam.text = hw.ramDisplay
    }

    private fun updateUI() {
        // Update switch without triggering listener
        switchService.setOnCheckedChangeListener(null)
        switchService.isChecked = isServiceActive
        switchService.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !isServiceActive) startMonitorService()
            else if (!isChecked && isServiceActive) stopMonitorService()
        }

        tvStatusDetail.text = if (isServiceActive) "MONITORING: ACTIVE" else "MONITORING: OFF"
    }

    private fun updatePermissionStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            ivCheckNotification.setImageResource(
                if (notifGranted) R.drawable.ic_check_circle else R.drawable.ic_circle_outline
            )
            ivCheckNotification.setColorFilter(ContextCompat.getColor(this,
                if (notifGranted) R.color.accent_green else R.color.text_secondary
            ))
            permissionNotification.visibility = View.VISIBLE
        } else {
            permissionNotification.visibility = View.GONE
        }

        val usageGranted = hasUsageAccess()
        ivCheckUsage.setImageResource(
            if (usageGranted) R.drawable.ic_check_circle else R.drawable.ic_circle_outline
        )
        ivCheckUsage.setColorFilter(ContextCompat.getColor(this,
            if (usageGranted) R.color.accent_green else R.color.text_secondary
        ))
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) updatePermissionStatus()
    }
}
