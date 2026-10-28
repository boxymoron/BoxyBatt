package com.boxymoron.boxybatt

import android.graphics.Typeface
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.boxymoron.boxybatt.util.BatteryHistoryStore
import com.boxymoron.boxybatt.view.BatteryChartView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Battery history activity with multi-layer chart, time range selection,
 * and comprehensive statistics including current draw and power.
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var historyStore: BatteryHistoryStore
    private lateinit var chartView: BatteryChartView
    private lateinit var tvRecordCount: TextView
    private lateinit var tvAvgLevel: TextView
    private lateinit var tvAvgTemp: TextView
    private lateinit var tvDrainRate: TextView
    private lateinit var llLogEntries: LinearLayout

    private var currentRange = TimeRange.HOURS_24

    enum class TimeRange(val label: String, val ms: Long) {
        HOURS_1("1h", 3600_000L),
        HOURS_6("6h", 6 * 3600_000L),
        HOURS_12("12h", 12 * 3600_000L),
        HOURS_24("24h", 24 * 3600_000L),
        DAYS_7("7d", 7 * 24 * 3600_000L);
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        historyStore = BatteryHistoryStore.getInstance(this)

        chartView = findViewById(R.id.chart_view)
        tvRecordCount = findViewById(R.id.tv_record_count)
        tvAvgLevel = findViewById(R.id.tv_avg_level)
        tvAvgTemp = findViewById(R.id.tv_avg_temp)
        tvDrainRate = findViewById(R.id.tv_drain_rate)
        llLogEntries = findViewById(R.id.ll_log_entries)

        // Back
        findViewById<android.widget.ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        // Time range chips
        val chip6h = findViewById<TextView>(R.id.chip_6h)
        val chip12h = findViewById<TextView>(R.id.chip_12h)
        val chip24h = findViewById<TextView>(R.id.chip_24h)
        val chip7d = findViewById<TextView>(R.id.chip_7d)

        val chips = mapOf(
            chip6h to TimeRange.HOURS_6,
            chip12h to TimeRange.HOURS_12,
            chip24h to TimeRange.HOURS_24,
            chip7d to TimeRange.DAYS_7
        )

        for ((chip, range) in chips) {
            chip.setOnClickListener {
                currentRange = range
                for ((c, r) in chips) {
                    c.setBackgroundResource(
                        if (r == range) R.drawable.bg_chip_active else R.drawable.bg_chip_neutral
                    )
                    c.setTextColor(getColor(
                        if (r == range) R.color.text_primary else R.color.text_secondary
                    ))
                }
                loadHistory()
            }
        }

        loadHistory()
    }

    override fun onResume() {
        super.onResume()
        loadHistory()
    }

    private fun loadHistory() {
        val since = System.currentTimeMillis() - currentRange.ms
        val entries = historyStore.getHistory(since)
        val totalRecords = historyStore.getRecordCount()

        tvRecordCount.text = "$totalRecords records"

        // Chart
        chartView.showCurrent = true
        chartView.showPower = false  // can be toggled
        chartView.setData(entries)

        // Stats
        if (entries.isNotEmpty()) {
            val avgLevel = entries.map { it.level }.average()
            val avgTemp = entries.map { it.temperature }.average()

            tvAvgLevel.text = "${avgLevel.toInt()}%"
            tvAvgTemp.text = String.format("%.1f°C", avgTemp)

            // Calculate drain rate (level change per hour during discharge)
            val dischargeEntries = entries.filter {
                it.status != android.os.BatteryManager.BATTERY_STATUS_CHARGING
            }
            if (dischargeEntries.size >= 2) {
                val first = dischargeEntries.first()
                val last = dischargeEntries.last()
                val levelDrop = first.level - last.level
                val timeHours = (last.timestamp - first.timestamp) / 3600_000.0
                if (timeHours > 0) {
                    val drainRate = levelDrop / timeHours
                    tvDrainRate.text = String.format("%.1f%%/h", drainRate)
                } else {
                    tvDrainRate.text = "--%/h"
                }
            } else {
                tvDrainRate.text = "--%/h"
            }
        } else {
            tvAvgLevel.text = "--%"
            tvAvgTemp.text = "--°C"
            tvDrainRate.text = "--%/h"
        }

        // Recent log entries with current draw info
        llLogEntries.removeAllViews()
        val recent = entries.takeLast(50).reversed()
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        for (entry in recent) {
            val row = TextView(this).apply {
                val statusChar = when (entry.status) {
                    android.os.BatteryManager.BATTERY_STATUS_CHARGING -> "⚡"
                    android.os.BatteryManager.BATTERY_STATUS_FULL -> "✓"
                    else -> "▼"
                }
                val currentStr = if (entry.currentNow != 0) {
                    "${kotlin.math.abs(entry.currentNow)}mA"
                } else "---"
                val powerStr = if (entry.currentNow != 0 && entry.voltage > 0) {
                    val watts = entry.voltage * kotlin.math.abs(entry.currentNow) / 1000.0
                    String.format("%.1fW", watts)
                } else "---"

                text = "${sdf.format(Date(entry.timestamp))}  " +
                       "${entry.level}%  " +
                       "${String.format("%.1f", entry.temperature)}°C  " +
                       "$currentStr  $powerStr  $statusChar"
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                textSize = 10f
                typeface = Typeface.MONOSPACE
                setPadding(0, 3.dp(), 0, 3.dp())
            }
            llLogEntries.addView(row)
        }

        if (entries.isEmpty()) {
            val emptyText = TextView(this).apply {
                text = "No history data. Enable monitoring service to start logging."
                setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
                textSize = 12f
                setPadding(0, 16.dp(), 0, 0)
            }
            llLogEntries.addView(emptyText)
        }
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
}
