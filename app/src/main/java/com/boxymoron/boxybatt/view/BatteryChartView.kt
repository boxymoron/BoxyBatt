package com.boxymoron.boxybatt.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.os.BatteryManager
import android.util.AttributeSet
import android.view.View
import com.boxymoron.boxybatt.R
import com.boxymoron.boxybatt.util.BatteryHistoryStore

/**
 * Custom chart view that draws battery level, temperature, current draw,
 * and power as line charts over time with charging region highlights.
 */
class BatteryChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var entries: List<BatteryHistoryStore.HistoryEntry> = emptyList()

    // Which chart layers to show
    var showLevel = true
    var showTemperature = true
    var showCurrent = true
    var showPower = false

    private val paintLevel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.accent_green)
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val paintTemp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.accent_orange)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val paintCurrent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.accent_cyan)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val paintPower = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.accent_red)
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
    }

    private val paintCharging = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.accent_cyan)
        alpha = 30
        style = Paint.Style.FILL
    }

    private val paintGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.divider)
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.text_tertiary)
        textSize = 22f
    }

    private val paintLevelFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.accent_green)
        alpha = 15
        style = Paint.Style.FILL
    }

    // Legend colors
    private val paintLegendLevel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.accent_green)
        textSize = 20f
    }
    private val paintLegendTemp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.accent_orange)
        textSize = 20f
    }
    private val paintLegendCurrent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.accent_cyan)
        textSize = 20f
    }
    private val paintLegendPower = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.accent_red)
        textSize = 20f
    }

    fun setData(data: List<BatteryHistoryStore.HistoryEntry>) {
        entries = data
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (entries.isEmpty()) {
            drawEmptyState(canvas)
            return
        }

        val w = width.toFloat()
        val h = height.toFloat()
        val paddingLeft = 48f
        val paddingRight = 12f
        val paddingTop = 24f  // room for legend
        val paddingBottom = 28f
        val chartW = w - paddingLeft - paddingRight
        val chartH = h - paddingTop - paddingBottom

        // Draw legend
        drawLegend(canvas, paddingLeft, 16f)

        // Draw grid
        for (i in 0..4) {
            val y = paddingTop + chartH * i / 4f
            canvas.drawLine(paddingLeft, y, w - paddingRight, y, paintGrid)
            val label = "${100 - i * 25}%"
            canvas.drawText(label, 2f, y + 8f, paintText)
        }

        // Time range
        val minTime = entries.first().timestamp
        val maxTime = entries.last().timestamp
        val timeRange = maxOf(maxTime - minTime, 1L)

        // Find ranges for normalization
        val temps = entries.map { it.temperature }
        val minTemp = (temps.minOrNull() ?: 20f) - 2f
        val maxTemp = (temps.maxOrNull() ?: 40f) + 2f
        val tempRange = maxOf(maxTemp - minTemp, 1f)

        val currents = entries.map { kotlin.math.abs(it.currentNow) }
        val maxCurrent = (currents.maxOrNull() ?: 1).coerceAtLeast(1)

        // Draw charging regions
        var chargingStart = -1f
        for (i in entries.indices) {
            val x = paddingLeft + ((entries[i].timestamp - minTime).toFloat() / timeRange) * chartW
            val isCharging = entries[i].status == BatteryManager.BATTERY_STATUS_CHARGING
            if (isCharging && chargingStart < 0) {
                chargingStart = x
            } else if (!isCharging && chargingStart >= 0) {
                canvas.drawRect(chargingStart, paddingTop, x, paddingTop + chartH, paintCharging)
                chargingStart = -1f
            }
        }
        if (chargingStart >= 0) {
            canvas.drawRect(chargingStart, paddingTop, paddingLeft + chartW, paddingTop + chartH, paintCharging)
        }

        // Draw level line with fill
        if (showLevel) {
            val levelPath = Path()
            val levelFillPath = Path()
            var firstPoint = true

            for (i in entries.indices) {
                val x = paddingLeft + ((entries[i].timestamp - minTime).toFloat() / timeRange) * chartW
                val y = paddingTop + chartH * (1f - entries[i].level / 100f)

                if (firstPoint) {
                    levelPath.moveTo(x, y)
                    levelFillPath.moveTo(x, paddingTop + chartH)
                    levelFillPath.lineTo(x, y)
                    firstPoint = false
                } else {
                    levelPath.lineTo(x, y)
                    levelFillPath.lineTo(x, y)
                }
            }
            val lastX = paddingLeft + ((entries.last().timestamp - minTime).toFloat() / timeRange) * chartW
            levelFillPath.lineTo(lastX, paddingTop + chartH)
            levelFillPath.close()

            canvas.drawPath(levelFillPath, paintLevelFill)
            canvas.drawPath(levelPath, paintLevel)
        }

        // Draw temperature line
        if (showTemperature) {
            val tempPath = Path()
            var firstPoint = true
            for (i in entries.indices) {
                val x = paddingLeft + ((entries[i].timestamp - minTime).toFloat() / timeRange) * chartW
                val normalizedTemp = (entries[i].temperature - minTemp) / tempRange
                val y = paddingTop + chartH * (1f - normalizedTemp)

                if (firstPoint) {
                    tempPath.moveTo(x, y)
                    firstPoint = false
                } else {
                    tempPath.lineTo(x, y)
                }
            }
            canvas.drawPath(tempPath, paintTemp)
        }

        // Draw current draw line (normalized to chart height)
        if (showCurrent && maxCurrent > 0) {
            val currentPath = Path()
            var firstPoint = true
            for (i in entries.indices) {
                val x = paddingLeft + ((entries[i].timestamp - minTime).toFloat() / timeRange) * chartW
                val normalized = kotlin.math.abs(entries[i].currentNow).toFloat() / maxCurrent
                val y = paddingTop + chartH * (1f - normalized)

                if (firstPoint) {
                    currentPath.moveTo(x, y)
                    firstPoint = false
                } else {
                    currentPath.lineTo(x, y)
                }
            }
            canvas.drawPath(currentPath, paintCurrent)
        }

        // Draw power line (V * I, normalized)
        if (showPower && maxCurrent > 0) {
            val powers = entries.map { it.voltage * kotlin.math.abs(it.currentNow) / 1000.0 }
            val maxPower = (powers.maxOrNull() ?: 1.0).coerceAtLeast(0.01)

            val powerPath = Path()
            var firstPoint = true
            for (i in entries.indices) {
                val x = paddingLeft + ((entries[i].timestamp - minTime).toFloat() / timeRange) * chartW
                val normalized = (powers[i] / maxPower).toFloat()
                val y = paddingTop + chartH * (1f - normalized)

                if (firstPoint) {
                    powerPath.moveTo(x, y)
                    firstPoint = false
                } else {
                    powerPath.lineTo(x, y)
                }
            }
            canvas.drawPath(powerPath, paintPower)
        }

        // Draw time labels
        val timeLabels = 4
        for (i in 0..timeLabels) {
            val t = minTime + timeRange * i / timeLabels
            val x = paddingLeft + chartW * i / timeLabels
            val label = formatTimeLabel(t)
            canvas.drawText(label, x, h - 4f, paintText)
        }
    }

    private fun drawLegend(canvas: Canvas, startX: Float, y: Float) {
        var x = startX
        if (showLevel) {
            canvas.drawText("● Level", x, y, paintLegendLevel)
            x += paintLegendLevel.measureText("● Level") + 16f
        }
        if (showTemperature) {
            canvas.drawText("● Temp", x, y, paintLegendTemp)
            x += paintLegendTemp.measureText("● Temp") + 16f
        }
        if (showCurrent) {
            canvas.drawText("● Current", x, y, paintLegendCurrent)
            x += paintLegendCurrent.measureText("● Current") + 16f
        }
        if (showPower) {
            canvas.drawText("● Power", x, y, paintLegendPower)
        }
    }

    private fun drawEmptyState(canvas: Canvas) {
        val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getColor(R.color.text_tertiary)
            textSize = 28f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            "No history data yet. Enable monitoring to start.",
            width / 2f, height / 2f, emptyPaint
        )
    }

    private fun formatTimeLabel(timestamp: Long): String {
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }
}
