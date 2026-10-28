package com.boxymoron.boxybatt

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.boxymoron.boxybatt.adapter.AppBatteryAdapter
import com.boxymoron.boxybatt.model.AppBatteryUsage
import com.boxymoron.boxybatt.util.AppUsageCollector
import kotlinx.coroutines.*

/**
 * Shows per-app battery usage with search, filter, and sort functionality.
 * Mirrors NetFence's MonitorActivity in structure and UI patterns.
 */
class AppUsageActivity : AppCompatActivity() {

    private lateinit var usageCollector: AppUsageCollector
    private lateinit var adapter: AppBatteryAdapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var allApps: List<AppBatteryUsage> = emptyList()
    private var currentFilter = ""
    private var filterMode = FilterMode.ALL
    private var sortMode = SortMode.DRAIN
    private var refreshJob: Job? = null

    private lateinit var tvTotalApps: TextView
    private lateinit var tvHeavyCount: TextView
    private lateinit var tvScreenTime: TextView
    private lateinit var chipAll: TextView
    private lateinit var chipHeavy: TextView
    private lateinit var chipLight: TextView

    enum class FilterMode { ALL, HEAVY, LIGHT }

    enum class SortMode(val label: String) {
        DRAIN("Sort: Drain"),
        CPU_TIME("Sort: CPU Time"),
        RAM("Sort: RAM"),
        USAGE("Sort: Usage %"),
        ALPHA("Sort: A-Z"),
        FOREGROUND("Sort: FG Time"),
        BACKGROUND("Sort: BG Time"),
        RECENT("Sort: Recent");
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_usage)

        usageCollector = AppUsageCollector.getInstance(this)

        // Views
        tvTotalApps = findViewById(R.id.tv_total_apps)
        tvHeavyCount = findViewById(R.id.tv_heavy_count)
        tvScreenTime = findViewById(R.id.tv_screen_time)
        chipAll = findViewById(R.id.chip_all)
        chipHeavy = findViewById(R.id.chip_heavy)
        chipLight = findViewById(R.id.chip_light)

        // Back
        findViewById<android.widget.ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        // Adapter
        adapter = AppBatteryAdapter(
            onItemClick = { app ->
                val intent = Intent(this, AppBatteryDetailActivity::class.java)
                intent.putExtra(AppBatteryDetailActivity.EXTRA_PACKAGE_NAME, app.packageName)
                startActivity(intent)
            }
        )

        val rv = findViewById<RecyclerView>(R.id.rv_apps)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        // Search
        val etSearch = findViewById<EditText>(R.id.et_search)
        etSearch.addTextChangedListener { text ->
            currentFilter = text?.toString() ?: ""
            applyFilters()
        }

        // Filter chips
        chipAll.setOnClickListener { setFilterMode(FilterMode.ALL) }
        chipHeavy.setOnClickListener { setFilterMode(FilterMode.HEAVY) }
        chipLight.setOnClickListener { setFilterMode(FilterMode.LIGHT) }

        // Sort dropdown
        val spinnerSort = findViewById<Spinner>(R.id.spinner_sort)
        val sortLabels = SortMode.entries.map { it.label }
        val sortAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, sortLabels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerSort.adapter = sortAdapter
        spinnerSort.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                (view as? TextView)?.apply {
                    setTextColor(getColor(R.color.text_secondary))
                    textSize = 11f
                }
                sortMode = SortMode.entries[position]
                applyFilters()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        loadApps()
    }

    override fun onResume() {
        super.onResume()
        loadApps()
        // Auto-refresh every 30s to detect new apps and update drain
        refreshJob = scope.launch {
            while (isActive) {
                delay(30_000)
                loadApps()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        refreshJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun loadApps() {
        scope.launch {
            val apps = withContext(Dispatchers.IO) {
                usageCollector.getAppUsage()
            }
            allApps = apps
            applyFilters()
            updateSummary()
        }
    }

    private fun applyFilters() {
        var filtered = allApps

        // Mode filter
        filtered = when (filterMode) {
            FilterMode.ALL -> filtered
            FilterMode.HEAVY -> filtered.filter {
                it.usageLevel == AppBatteryUsage.UsageLevel.HEAVY
            }
            FilterMode.LIGHT -> filtered.filter {
                it.usageLevel == AppBatteryUsage.UsageLevel.LIGHT
            }
        }

        // Text filter
        if (currentFilter.isNotBlank()) {
            filtered = filtered.filter {
                it.appName.contains(currentFilter, ignoreCase = true) ||
                it.packageName.contains(currentFilter, ignoreCase = true)
            }
        }

        // Sort
        filtered = when (sortMode) {
            SortMode.DRAIN -> filtered.sortedByDescending { it.estimatedDrainMAh }
            SortMode.CPU_TIME -> filtered.sortedByDescending { it.cpuTimeMs }
            SortMode.RAM -> filtered.sortedByDescending { it.ramMB }
            SortMode.USAGE -> filtered.sortedByDescending { it.usagePercent }
            SortMode.ALPHA -> filtered.sortedBy { it.appName.lowercase() }
            SortMode.FOREGROUND -> filtered.sortedByDescending { it.foregroundTimeMs }
            SortMode.BACKGROUND -> filtered.sortedByDescending { it.backgroundTimeMs }
            SortMode.RECENT -> filtered.sortedByDescending { it.lastTimeUsed }
        }

        adapter.submitList(filtered)
    }

    private fun setFilterMode(mode: FilterMode) {
        filterMode = mode
        chipAll.setBackgroundResource(
            if (mode == FilterMode.ALL) R.drawable.bg_chip_active else R.drawable.bg_chip_neutral
        )
        chipAll.setTextColor(getColor(
            if (mode == FilterMode.ALL) R.color.text_primary else R.color.text_secondary
        ))
        chipHeavy.setBackgroundResource(
            if (mode == FilterMode.HEAVY) R.drawable.bg_chip_active else R.drawable.bg_chip_neutral
        )
        chipHeavy.setTextColor(getColor(
            if (mode == FilterMode.HEAVY) R.color.text_primary else R.color.text_secondary
        ))
        chipLight.setBackgroundResource(
            if (mode == FilterMode.LIGHT) R.drawable.bg_chip_active else R.drawable.bg_chip_neutral
        )
        chipLight.setTextColor(getColor(
            if (mode == FilterMode.LIGHT) R.color.text_primary else R.color.text_secondary
        ))
        applyFilters()
    }

    private fun updateSummary() {
        tvTotalApps.text = "${allApps.size}"
        tvHeavyCount.text = "${allApps.count { it.usageLevel == AppBatteryUsage.UsageLevel.HEAVY }}"

        // Total foreground time
        val totalFg = allApps.sumOf { it.foregroundTimeMs }
        tvScreenTime.text = formatDuration(totalFg)
    }

    private fun formatDuration(ms: Long): String {
        val hours = ms / 3600_000
        val minutes = (ms % 3600_000) / 60_000
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }
}
