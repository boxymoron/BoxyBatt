package com.boxymoron.boxybatt

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.boxymoron.boxybatt.service.BatteryMonitorService
import com.boxymoron.boxybatt.util.BatteryStatsCollector

/**
 * Advanced Settings screen:
 * - Polling interval configuration (slider + millisecond precision input)
 * - Data source probe: shows all available sources with live values
 */
class AdvancedSettingsActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "boxybatt_prefs"
        private const val KEY_POLL_INTERVAL_MS = "poll_interval_ms"
        private const val DEFAULT_POLL_INTERVAL_MS = 5_000L  // 5 seconds

        // Slider range: 1,000ms (1s) to 1,800,000ms (30min)
        private const val MIN_INTERVAL_MS = 1_000L
        private const val MAX_INTERVAL_MS = 1_800_000L
    }

    private lateinit var tvIntervalDisplay: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var etIntervalMs: EditText

    private var currentIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_advanced_settings)

        // Back
        findViewById<android.widget.ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        // Load saved interval
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        currentIntervalMs = prefs.getLong(KEY_POLL_INTERVAL_MS, DEFAULT_POLL_INTERVAL_MS)

        // Polling interval controls
        tvIntervalDisplay = findViewById(R.id.tv_interval_display)
        seekBar = findViewById(R.id.seekbar_interval)
        etIntervalMs = findViewById(R.id.et_interval_ms)

        // Initialize slider position from current value
        seekBar.progress = msToSliderProgress(currentIntervalMs)
        updateIntervalDisplay(currentIntervalMs)
        etIntervalMs.setText(currentIntervalMs.toString())

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    currentIntervalMs = sliderProgressToMs(progress)
                    updateIntervalDisplay(currentIntervalMs)
                    etIntervalMs.setText(currentIntervalMs.toString())
                    saveInterval(currentIntervalMs)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                notifyServiceOfIntervalChange()
            }
        })

        // Apply button for precise ms input
        findViewById<Button>(R.id.btn_apply_interval).setOnClickListener {
            val text = etIntervalMs.text.toString()
            val ms = text.toLongOrNull()
            if (ms != null && ms >= MIN_INTERVAL_MS && ms <= MAX_INTERVAL_MS) {
                currentIntervalMs = ms
                seekBar.progress = msToSliderProgress(ms)
                updateIntervalDisplay(ms)
                saveInterval(ms)
                notifyServiceOfIntervalChange()
                Toast.makeText(this, "Interval set to ${formatInterval(ms)}", Toast.LENGTH_SHORT).show()
            } else if (ms != null && ms < MIN_INTERVAL_MS) {
                // Allow sub-second? Clamp to minimum
                currentIntervalMs = MIN_INTERVAL_MS
                seekBar.progress = 0
                updateIntervalDisplay(MIN_INTERVAL_MS)
                etIntervalMs.setText(MIN_INTERVAL_MS.toString())
                saveInterval(MIN_INTERVAL_MS)
                notifyServiceOfIntervalChange()
                Toast.makeText(this, "Minimum: ${MIN_INTERVAL_MS}ms", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Invalid value. Range: ${MIN_INTERVAL_MS}-${MAX_INTERVAL_MS}ms", Toast.LENGTH_SHORT).show()
            }
        }

        // Data sources
        loadDataSources()

        findViewById<Button>(R.id.btn_refresh_sources).setOnClickListener {
            loadDataSources()
            Toast.makeText(this, "Data sources refreshed", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Convert slider position (0-100) to milliseconds using a logarithmic scale.
     * This gives fine control at the low end (1s-60s) and coarse at the high end.
     */
    private fun sliderProgressToMs(progress: Int): Long {
        val ratio = progress / 100.0
        // Logarithmic: ms = MIN * (MAX/MIN)^ratio
        val ms = MIN_INTERVAL_MS * Math.pow(
            (MAX_INTERVAL_MS.toDouble() / MIN_INTERVAL_MS), ratio
        )
        return ms.toLong()
    }

    /**
     * Convert milliseconds to slider position (0-100).
     */
    private fun msToSliderProgress(ms: Long): Int {
        val clamped = ms.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
        // Inverse of logarithmic: ratio = log(ms/MIN) / log(MAX/MIN)
        val ratio = Math.log(clamped.toDouble() / MIN_INTERVAL_MS) /
                Math.log(MAX_INTERVAL_MS.toDouble() / MIN_INTERVAL_MS)
        return (ratio * 100).toInt().coerceIn(0, 100)
    }

    private fun updateIntervalDisplay(ms: Long) {
        tvIntervalDisplay.text = formatInterval(ms)
    }

    private fun formatInterval(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val millis = ms % 1000
        return when {
            minutes > 0 && millis > 0 -> "${minutes}m ${seconds}s ${millis}ms"
            minutes > 0 -> "${minutes}m ${seconds}s"
            millis > 0 -> "${seconds}s ${millis}ms"
            else -> "${seconds}s"
        }
    }

    private fun saveInterval(ms: Long) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putLong(KEY_POLL_INTERVAL_MS, ms)
            .apply()
    }

    private fun notifyServiceOfIntervalChange() {
        // If service is running, tell it to update its interval
        BatteryMonitorService.instance?.updatePollInterval(currentIntervalMs)
    }

    // ===== Data Sources =====

    private fun loadDataSources() {
        val sources = BatteryStatsCollector.probeAllSources(this)

        // Show BATTERY_STATS permission status
        val llPermStatus = findViewById<LinearLayout>(R.id.ll_info_sources)

        // Check if batterystats permission is available
        val hasBatteryStats = com.boxymoron.boxybatt.util.BatteryStatsParser
            .hasBatteryStatsPermission(this)

        val permRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_permission_item)
            setPadding(10.dp(), 8.dp(), 10.dp(), 8.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 6.dp() }
        }

        val permTitle = TextView(this).apply {
            text = if (hasBatteryStats) "● BATTERY_STATS: Granted ✓" else "○ BATTERY_STATS: Not granted"
            setTextColor(ContextCompat.getColor(context,
                if (hasBatteryStats) R.color.accent_green else R.color.accent_orange
            ))
            textSize = 12f
            typeface = Typeface.MONOSPACE
        }
        permRow.addView(permTitle)

        if (!hasBatteryStats) {
            val permHint = TextView(this).apply {
                text = "For precise per-app drain data, grant via ADB:\nadb shell pm grant com.boxymoron.boxybatt android.permission.BATTERY_STATS"
                setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
                textSize = 9f
                typeface = Typeface.MONOSPACE
                setPadding(0, 4.dp(), 0, 0)
            }
            permRow.addView(permHint)
        } else {
            val permHint = TextView(this).apply {
                text = "Using Android's power attribution engine for accurate per-app drain"
                setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
                textSize = 9f
                setPadding(0, 4.dp(), 0, 0)
            }
            permRow.addView(permHint)
        }

        val llCurrent = findViewById<LinearLayout>(R.id.ll_current_sources)
        val llCharge = findViewById<LinearLayout>(R.id.ll_charge_sources)
        val llEnergy = findViewById<LinearLayout>(R.id.ll_energy_sources)
        val llInfo = findViewById<LinearLayout>(R.id.ll_info_sources)

        llCurrent.removeAllViews()
        llCharge.removeAllViews()
        llEnergy.removeAllViews()
        llInfo.removeAllViews()
        llInfo.addView(permRow)

        for (source in sources) {
            val container = when (source.category) {
                "current" -> llCurrent
                "charge" -> llCharge
                "energy" -> llEnergy
                else -> llInfo
            }
            container.addView(buildSourceRow(source))
        }
    }

    private fun buildSourceRow(source: BatteryStatsCollector.DataSourceInfo): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 2.dp() }
            background = ContextCompat.getDrawable(context, R.drawable.bg_permission_item)
            setPadding(10.dp(), 8.dp(), 10.dp(), 8.dp())
            gravity = Gravity.CENTER_VERTICAL
        }

        // Status indicator dot
        val dot = TextView(this).apply {
            text = if (source.available) "●" else "○"
            setTextColor(ContextCompat.getColor(context,
                if (source.available) R.color.accent_green else R.color.text_tertiary
            ))
            textSize = 10f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 8.dp() }
        }
        row.addView(dot)

        // Source name
        val nameView = TextView(this).apply {
            text = source.name
            setTextColor(ContextCompat.getColor(context,
                if (source.available) R.color.text_primary else R.color.text_tertiary
            ))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        row.addView(nameView)

        // Value
        val valueView = TextView(this).apply {
            text = source.rawValue
            setTextColor(ContextCompat.getColor(context,
                when {
                    source.available -> R.color.accent_green
                    source.rawValue.contains("denied") -> R.color.accent_red
                    else -> R.color.text_tertiary
                }
            ))
            textSize = 10f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.END
            maxWidth = 180.dp()
        }
        row.addView(valueView)

        return row
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
}
