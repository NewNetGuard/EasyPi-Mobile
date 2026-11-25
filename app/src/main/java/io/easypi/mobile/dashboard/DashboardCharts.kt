package io.easypi.mobile.dashboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

private val CARD = Color.rgb(14, 31, 51)
private val BORDER = Color.rgb(35, 63, 88)
private val GRID = Color.rgb(34, 55, 77)
private val WHITE = Color.WHITE
private val MUTED = Color.rgb(151, 173, 197)
private val CYAN = Color.rgb(47, 219, 190)
private val BLUE = Color.rgb(71, 151, 255)
private val CORAL = Color.rgb(255, 98, 112)
private val AMBER = Color.rgb(255, 184, 72)

abstract class ProtectionChartView(context: Context) : View(context) {
    protected var stats: ProtectionStats? = null
    protected var message: String? = "Loading protection data…"
    protected val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val density = resources.displayMetrics.density

    fun showStats(value: ProtectionStats) {
        stats = value
        message = null
        contentDescription = "This phone: ${value.totalQueries} queries today, ${value.blockedQueries} blocked"
        invalidate()
    }

    fun showMessage(value: String) {
        stats = null
        message = value
        contentDescription = value
        invalidate()
    }

    protected fun dp(value: Float) = value * density

    protected fun card(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = CARD
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), dp(20f), dp(20f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = BORDER
        canvas.drawRoundRect(dp(.5f), dp(.5f), width - dp(.5f), height - dp(.5f), dp(20f), dp(20f), paint)
    }

    protected fun text(canvas: Canvas, value: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false) {
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = color
        paint.textSize = dp(size)
        paint.typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        canvas.drawText(value, x, y, paint)
    }

    protected fun compact(value: Int): String = when {
        value >= 1_000_000 -> String.format(Locale.ENGLISH, "%.1fM", value / 1_000_000f)
        value >= 1_000 -> String.format(Locale.ENGLISH, "%.1fK", value / 1_000f)
        else -> value.toString()
    }

    protected fun dateLabel(): String = (stats?.sampledAt?.toLocalDate() ?: LocalDate.now()).format(DATE_FORMAT)

    protected fun emptyMessage(canvas: Canvas) {
        message?.let { text(canvas, it, dp(18f), height / 2f + dp(5f), 13f, MUTED) }
    }

    private companion object {
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)
    }
}

class TrafficPulseView(context: Context) : ProtectionChartView(context) {
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        card(canvas)
        text(canvas, "THIS PHONE · DNS", dp(18f), dp(29f), 11f, BLUE, true)
        text(canvas, "DNS requests today", dp(18f), dp(50f), 16f, WHITE, true)
        text(canvas, dateLabel(), dp(18f), dp(69f), 10f, MUTED)
        val current = stats ?: return emptyMessage(canvas)
        text(canvas, compact(current.totalQueries), width - dp(122f), dp(29f), 15f, CYAN, true)
        text(canvas, "total", width - dp(76f), dp(29f), 10f, MUTED)
        text(canvas, compact(current.blockedQueries), width - dp(122f), dp(50f), 13f, CORAL, true)
        text(canvas, "blocked", width - dp(76f), dp(50f), 10f, MUTED)

        val chart = RectF(dp(36f), dp(100f), width - dp(18f), height - dp(46f))
        val hourWidth = chart.width() / 24f
        val elapsedHours = current.sampledAt.toLocalTime().toSecondOfDay() / 3600f
        val observed = current.hourly.take(current.sampledAt.hour + 1)
        val peak = max(1, observed.maxOfOrNull { it.total } ?: 0)
        val nowX = chart.left + chart.width() * elapsedHours / 24f

        // Future hours have no observations: shade them instead of plotting zeros.
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(35, 151, 173, 197)
        canvas.drawRect(nowX, chart.top, chart.right, chart.bottom, paint)
        repeat(3) { index ->
            val y = chart.top + chart.height() * index / 2f
            paint.strokeWidth = dp(1f)
            paint.color = GRID
            canvas.drawLine(chart.left, y, chart.right, y, paint)
            text(canvas, compact((peak * (2 - index) + 1) / 2), dp(8f), y + dp(3f), 8f, MUTED)
        }
        observed.forEachIndexed { hour, point ->
            val left = chart.left + hourWidth * hour + dp(1f)
            val right = chart.left + hourWidth * (hour + 1) - dp(1f)
            val totalY = chart.bottom - chart.height() * point.total / peak
            val blockedY = chart.bottom - chart.height() * point.blocked / peak
            paint.color = CYAN
            canvas.drawRect(left, totalY, right, chart.bottom, paint)
            paint.color = CORAL
            canvas.drawRect(left, blockedY, right, chart.bottom, paint)
        }
        paint.color = MUTED
        paint.strokeWidth = dp(1f)
        canvas.drawLine(nowX, chart.top, nowX, chart.bottom, paint)
        val nowLabel = "NOW " + current.sampledAt.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH))
        paint.textSize = dp(8f)
        val nowLabelWidth = paint.measureText(nowLabel)
        text(canvas, nowLabel, (nowX - nowLabelWidth / 2).coerceIn(chart.left, chart.right - nowLabelWidth), chart.top - dp(5f), 8f, MUTED)
        listOf(0, 6, 12, 18, 24).forEach { hour ->
            val label = String.format(Locale.ENGLISH, "%02d", hour)
            paint.textSize = dp(9f)
            val labelWidth = paint.measureText(label)
            val x = chart.left + chart.width() * hour / 24f
            text(canvas, label, x - labelWidth / 2, chart.bottom + dp(17f), 9f, MUTED)
        }
        if (current.totalQueries == 0) {
            text(canvas, "No DNS requests observed today", chart.left + dp(6f), chart.centerY(), 10f, MUTED)
        }
        text(canvas, "Hourly DNS requests observed by EasyPi VPN", dp(18f), height - dp(10f), 9f, MUTED)
    }
}

class ProtectionOrbitView(context: Context) : ProtectionChartView(context) {
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        card(canvas)
        text(canvas, "THIS PHONE · DNS PROTECTION", dp(18f), dp(29f), 11f, AMBER, true)
        text(canvas, "DNS requests blocked today", dp(18f), dp(50f), 16f, WHITE, true)
        text(canvas, dateLabel(), dp(18f), dp(69f), 10f, MUTED)
        val current = stats ?: return emptyMessage(canvas)

        val centerX = dp(72f)
        val centerY = dp(129f)
        val radius = dp(43f)
        val ring = RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(13f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.rgb(30, 56, 75)
        canvas.drawArc(ring, -90f, 360f, false, paint)
        val sweep = current.blockPercentage.coerceIn(0f, 100f) * 3.6f
        if (sweep > 0f) {
            paint.color = CORAL
            canvas.drawArc(ring, -90f, sweep, false, paint)
        }
        val percent = percentageLabel(current.blockPercentage)
        paint.textSize = dp(15f)
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        val percentWidth = paint.measureText(percent)
        text(canvas, percent, centerX - percentWidth / 2f, centerY + dp(4f), 15f, WHITE, true)
        text(canvas, "BLOCKED", centerX - dp(23f), centerY + dp(20f), 8f, MUTED, true)
        text(canvas, "${compact(current.blockedQueries)} stopped", dp(30f), dp(198f), 11f, CORAL, true)

        val startX = dp(137f)
        text(canvas, "TOP BLOCKED DNS", startX, dp(91f), 9f, MUTED, true)
        if (current.topBlocked.isEmpty()) {
            text(canvas, "No DNS requests blocked", startX, dp(121f), 11f, MUTED)
            return
        }
        val maximum = max(1, current.topBlocked.maxOf { it.hits })
        current.topBlocked.take(3).forEachIndexed { index, item ->
            val baseline = dp(116f + index * 36f)
            val available = width - startX - dp(22f)
            paint.textSize = dp(11f)
            val countWidth = paint.measureText(item.hits.toString())
            val labelWidth = available - countWidth - dp(12f)
            val count = paint.breakText(item.domain, true, labelWidth, null)
            val label = if (count < item.domain.length) item.domain.take(max(1, count - 1)) + "…" else item.domain
            text(canvas, label, startX, baseline, 11f, WHITE)
            text(canvas, item.hits.toString(), width - dp(22f) - countWidth, baseline, 11f, CORAL, true)
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(32, 58, 78)
            canvas.drawRoundRect(startX, baseline + dp(8f), width - dp(22f), baseline + dp(13f), dp(3f), dp(3f), paint)
            paint.color = if (index == 0) CORAL else AMBER
            val barWidth = (available * item.hits / maximum).coerceAtLeast(dp(6f))
            canvas.drawRoundRect(startX, baseline + dp(8f), startX + barWidth, baseline + dp(13f), dp(3f), dp(3f), paint)
        }
    }
}
