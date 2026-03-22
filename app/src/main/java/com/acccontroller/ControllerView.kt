package com.acccontroller

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max

class ControllerView(context: Context) : View(context) {

    var onThrottleChanged: ((Float) -> Unit)? = null
    var onBrakeChanged: ((Float) -> Unit)? = null
    var onRecenter: (() -> Unit)? = null
    var onSensitivityUp: (() -> Unit)? = null
    var onSensitivityDown: (() -> Unit)? = null

    private var steeringValue = 0f
    private var throttleValue = 0f
    private var brakeValue = 0f
    private var isConnected = false
    var sensitivityLevel = 3  // 1-5 display only

    private val activePointers = mutableMapOf<Int, PedalTracker>()

    private var brakeZone = RectF()
    private var throttleZone = RectF()
    private var steeringBarRect = RectF()
    private var recenterBtn = RectF()
    private var sensUpBtn = RectF()
    private var sensDownBtn = RectF()

    private val swipeFullRange = 350f

    // --- Pre-allocated paints (never allocate in onDraw) ---

    private val bgPaint = Paint().apply {
        color = Color.parseColor(COLOR_BG); style = Paint.Style.FILL
    }
    private val zoneBgPaint = Paint().apply {
        style = Paint.Style.FILL; isAntiAlias = true
    }
    private val zoneBorderPaint = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; isAntiAlias = true
    }
    private val barFillPaint = Paint().apply {
        style = Paint.Style.FILL; isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        color = Color.parseColor(COLOR_TEXT); textSize = 36f
        typeface = Typeface.create("monospace", Typeface.BOLD)
        textAlign = Paint.Align.CENTER; isAntiAlias = true
    }
    private val bigTextPaint = Paint().apply {
        color = Color.WHITE; textSize = 52f
        typeface = Typeface.create("monospace", Typeface.BOLD)
        textAlign = Paint.Align.CENTER; isAntiAlias = true
    }
    private val btnPaint = Paint().apply {
        style = Paint.Style.FILL; isAntiAlias = true
    }
    private val btnTextPaint = Paint().apply {
        color = Color.WHITE; textSize = 26f
        typeface = Typeface.create("monospace", Typeface.BOLD)
        textAlign = Paint.Align.CENTER; isAntiAlias = true
    }
    private val connectedPaint = Paint().apply {
        style = Paint.Style.FILL; isAntiAlias = true; textSize = 28f
        typeface = Typeface.create("monospace", Typeface.NORMAL)
        textAlign = Paint.Align.LEFT
    }
    private val centerLinePaint = Paint().apply {
        color = Color.parseColor(COLOR_CENTER_LINE); style = Paint.Style.STROKE
        strokeWidth = 1f; pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val gridPaint = Paint().apply {
        color = Color.parseColor(COLOR_GRID); style = Paint.Style.STROKE; strokeWidth = 1f
    }
    private val tickPaint = Paint().apply {
        color = Color.parseColor(COLOR_TICK); style = Paint.Style.STROKE; strokeWidth = 1.5f
    }
    private val arrowPaint = Paint().apply {
        color = Color.parseColor(COLOR_ARROW); style = Paint.Style.STROKE
        strokeWidth = 3f; isAntiAlias = true
    }
    private val steeringBgPaint = Paint().apply {
        color = Color.parseColor(COLOR_STEERING_BG); style = Paint.Style.FILL
    }
    private val needlePaint = Paint().apply {
        color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true
    }
    private val steeringBorderPaint = Paint().apply {
        color = Color.parseColor(COLOR_STEERING_BORDER); style = Paint.Style.STROKE; strokeWidth = 1.5f
    }
    private val btnBorderPaint = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f; isAntiAlias = true
    }
    private val dotPaint = Paint().apply {
        style = Paint.Style.FILL; isAntiAlias = true
    }

    private var brakeGradient: LinearGradient? = null
    private var throttleGradient: LinearGradient? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val padding = 24f
        val zoneW = w * 0.22f
        val steerH = 70f
        val steerY = (h - steerH) / 2f

        brakeZone      = RectF(padding, padding, zoneW, h - padding)
        throttleZone   = RectF(w - zoneW, padding, w - padding, h - padding)
        steeringBarRect = RectF(zoneW + 40f, steerY, w - zoneW - 40f, steerY + steerH)

        val btnY = steerY + steerH + 20f
        val btnW = 120f
        val btnH = 52f
        val cx = w / 2f
        recenterBtn = RectF(cx - btnW/2, btnY, cx + btnW/2, btnY + btnH)
        sensDownBtn = RectF(cx - btnW*1.8f, btnY, cx - btnW*0.8f, btnY + btnH)
        sensUpBtn   = RectF(cx + btnW*0.8f, btnY, cx + btnW*1.8f, btnY + btnH)

        brakeGradient = LinearGradient(
            brakeZone.left, brakeZone.bottom, brakeZone.left, brakeZone.top,
            intArrayOf(Color.parseColor(COLOR_BRAKE_LOW), Color.parseColor(COLOR_BRAKE_MID), Color.parseColor(COLOR_BRAKE_HIGH)),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
        )
        throttleGradient = LinearGradient(
            throttleZone.left, throttleZone.bottom, throttleZone.left, throttleZone.top,
            intArrayOf(Color.parseColor(COLOR_THROTTLE_LOW), Color.parseColor(COLOR_THROTTLE_MID), Color.parseColor(COLOR_THROTTLE_HIGH)),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        drawGrid(canvas, w, h)
        drawPedalZone(canvas, brakeZone, brakeValue, isBrake = true)
        drawPedalZone(canvas, throttleZone, throttleValue, isBrake = false)
        drawSteeringBar(canvas, w, h)
        drawButtons(canvas)
        drawStatus(canvas, w, h)
    }

    private fun drawGrid(canvas: Canvas, w: Float, h: Float) {
        val step = 60f
        var x = 0f; while (x < w) { canvas.drawLine(x, 0f, x, h, gridPaint); x += step }
        var y = 0f; while (y < h) { canvas.drawLine(0f, y, w, y, gridPaint); y += step }
    }

    private fun drawPedalZone(canvas: Canvas, zone: RectF, value: Float, isBrake: Boolean) {
        val r = 20f
        zoneBgPaint.color = Color.parseColor(COLOR_ZONE_BG)
        canvas.drawRoundRect(zone, r, r, zoneBgPaint)

        val fillH = zone.height() * value
        if (fillH > 8f) {
            val fillRect = RectF(zone.left + 4, zone.bottom - fillH, zone.right - 4, zone.bottom - 4)
            barFillPaint.shader = if (isBrake) brakeGradient else throttleGradient
            canvas.drawRoundRect(fillRect, 12f, 12f, barFillPaint)
            barFillPaint.shader = null
        }

        for (i in 1..9) {
            val ty = zone.top + zone.height() * (i / 10f)
            canvas.drawLine(zone.left + 12, ty, zone.right - 12, ty, tickPaint)
        }

        zoneBorderPaint.color = if (isBrake) Color.parseColor(COLOR_BRAKE_BORDER) else Color.parseColor(COLOR_THROTTLE_BORDER)
        canvas.drawRoundRect(zone, r, r, zoneBorderPaint)

        val label = if (isBrake) "BRAKE" else "THROTTLE"
        textPaint.color = if (isBrake) Color.parseColor(COLOR_BRAKE_LABEL) else Color.parseColor(COLOR_THROTTLE_LABEL)
        textPaint.textSize = 28f
        canvas.drawText(label, zone.centerX(), zone.top + 42f, textPaint)
        bigTextPaint.textSize = 50f
        canvas.drawText("${(value * 100).toInt()}%", zone.centerX(), zone.bottom - 26f, bigTextPaint)

        if (value < 0.02f) {
            val cx = zone.centerX(); val my = zone.centerY()
            canvas.drawLine(cx, my - 40f, cx, my + 40f, arrowPaint)
            canvas.drawLine(cx, my + 40f, cx - 18f, my + 16f, arrowPaint)
            canvas.drawLine(cx, my + 40f, cx + 18f, my + 16f, arrowPaint)
        }
    }

    private fun drawSteeringBar(canvas: Canvas, w: Float, h: Float) {
        val bar = steeringBarRect
        val cx = bar.centerX()
        val r = bar.height() / 2f

        canvas.drawRoundRect(bar, r, r, steeringBgPaint)

        val halfW = bar.width() / 2f
        if (abs(steeringValue) > 0.02f) {
            val fillEnd = cx + steeringValue * halfW
            val fillRect = if (steeringValue >= 0f)
                RectF(cx, bar.top + 4, fillEnd, bar.bottom - 4)
            else
                RectF(fillEnd, bar.top + 4, cx, bar.bottom - 4)
            barFillPaint.color = when {
                abs(steeringValue) > 0.8f -> Color.parseColor(COLOR_STEER_HIGH)
                abs(steeringValue) > 0.5f -> Color.parseColor(COLOR_STEER_MID)
                else                      -> Color.parseColor(COLOR_STEER_NORMAL)
            }
            canvas.drawRect(fillRect, barFillPaint)
        }

        canvas.drawLine(cx, bar.top - 8, cx, bar.bottom + 8, centerLinePaint)

        val needleX = cx + steeringValue * halfW
        canvas.drawRoundRect(RectF(needleX - 4f, bar.top - 8, needleX + 4f, bar.bottom + 8), 4f, 4f, needlePaint)
        canvas.drawRoundRect(bar, r, r, steeringBorderPaint)

        textPaint.color = Color.parseColor(COLOR_TEXT_LR); textPaint.textSize = 24f
        canvas.drawText("L", bar.left + 28f, bar.centerY() + 9f, textPaint)
        canvas.drawText("R", bar.right - 28f, bar.centerY() + 9f, textPaint)

        textPaint.color = Color.parseColor(COLOR_TEXT_STEER); textPaint.textSize = 26f
        val dir = if (steeringValue > 0.02f) "R" else if (steeringValue < -0.02f) "L" else ""
        canvas.drawText("STEER  ${(abs(steeringValue) * 100).toInt()}% $dir", cx, bar.top - 18f, textPaint)
    }

    private fun drawButtons(canvas: Canvas) {
        // Recenter button
        btnPaint.color = Color.parseColor(COLOR_BTN_RECENTER)
        canvas.drawRoundRect(recenterBtn, 12f, 12f, btnPaint)
        btnBorderPaint.color = Color.parseColor(COLOR_BTN_RECENTER_BORDER)
        canvas.drawRoundRect(recenterBtn, 12f, 12f, btnBorderPaint)
        btnTextPaint.textSize = 22f
        canvas.drawText("⊕ CENTER", recenterBtn.centerX(), recenterBtn.centerY() + 8f, btnTextPaint)

        // Sensitivity down
        btnPaint.color = Color.parseColor(COLOR_BTN_SENS_DOWN)
        canvas.drawRoundRect(sensDownBtn, 12f, 12f, btnPaint)
        btnBorderPaint.color = Color.parseColor(COLOR_BTN_SENS_DOWN_BORDER)
        canvas.drawRoundRect(sensDownBtn, 12f, 12f, btnBorderPaint)
        canvas.drawText("◀ SENS", sensDownBtn.centerX(), sensDownBtn.centerY() + 8f, btnTextPaint)

        // Sensitivity up
        btnPaint.color = Color.parseColor(COLOR_BTN_SENS_UP)
        canvas.drawRoundRect(sensUpBtn, 12f, 12f, btnPaint)
        btnBorderPaint.color = Color.parseColor(COLOR_BTN_SENS_UP_BORDER)
        canvas.drawRoundRect(sensUpBtn, 12f, 12f, btnBorderPaint)
        canvas.drawText("SENS ▶", sensUpBtn.centerX(), sensUpBtn.centerY() + 8f, btnTextPaint)

        // Sensitivity level dots
        val cx = width / 2f
        val dotY = sensDownBtn.bottom + 22f
        for (i in 1..5) {
            val dotX = cx + (i - 3) * 22f
            dotPaint.color = if (i <= sensitivityLevel) Color.parseColor(COLOR_CONNECTED) else Color.parseColor(COLOR_DOT_INACTIVE)
            canvas.drawCircle(dotX, dotY, 7f, dotPaint)
        }
        textPaint.color = Color.parseColor(COLOR_TEXT_SENS); textPaint.textSize = 20f
        canvas.drawText("SENSITIVITY", cx, dotY + 22f, textPaint)
    }

    private fun drawStatus(canvas: Canvas, w: Float, h: Float) {
        val dotX = w - 40f; val dotY = 28f
        connectedPaint.color = if (isConnected) Color.parseColor(COLOR_CONNECTED) else Color.parseColor(COLOR_DISCONNECTED)
        canvas.drawCircle(dotX, dotY, 8f, connectedPaint)
        connectedPaint.color = Color.parseColor(COLOR_TEXT_STATUS)
        connectedPaint.textSize = 22f
        connectedPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(if (isConnected) "CONNECTED" else "CONNECTING...", dotX - 18f, dotY + 7f, connectedPaint)
        textPaint.color = Color.parseColor(COLOR_TITLE); textPaint.textSize = 22f
        canvas.drawText("ACC CONTROLLER", w / 2f, 28f, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val pIdx = event.actionIndex
        val pId = event.getPointerId(pIdx)

        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = event.getX(pIdx)
                val y = event.getY(pIdx)

                if (recenterBtn.contains(x, y)) { onRecenter?.invoke();      invalidate(); return true }
                if (sensUpBtn.contains(x, y))   { onSensitivityUp?.invoke(); invalidate(); return true }
                if (sensDownBtn.contains(x, y)) { onSensitivityDown?.invoke(); invalidate(); return true }

                val zone = when {
                    brakeZone.contains(x, y)    -> PedalZone.BRAKE
                    throttleZone.contains(x, y) -> PedalZone.THROTTLE
                    else                        -> null
                }
                if (zone != null) {
                    activePointers[pId] = PedalTracker(zone, y, getCurrentValue(zone))
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val tracker = activePointers[id] ?: continue
                    val delta = tracker.startY - event.getY(i)
                    val newValue = (tracker.startValue + delta / swipeFullRange).coerceIn(0f, 1f)
                    when (tracker.zone) {
                        PedalZone.BRAKE    -> { brakeValue    = newValue; onBrakeChanged?.invoke(brakeValue) }
                        PedalZone.THROTTLE -> { throttleValue = newValue; onThrottleChanged?.invoke(throttleValue) }
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val tracker = activePointers.remove(pId)
                if (tracker != null) {
                    when (tracker.zone) {
                        PedalZone.BRAKE    -> { brakeValue    = 0f; onBrakeChanged?.invoke(0f) }
                        PedalZone.THROTTLE -> { throttleValue = 0f; onThrottleChanged?.invoke(0f) }
                    }
                    invalidate()
                }
            }
        }
        return true
    }

    private fun getCurrentValue(zone: PedalZone) = when (zone) {
        PedalZone.BRAKE    -> brakeValue
        PedalZone.THROTTLE -> throttleValue
    }

    fun updateSteering(value: Float) { steeringValue = value; invalidate() }
    fun setConnected(connected: Boolean) { isConnected = connected; invalidate() }

    enum class PedalZone { BRAKE, THROTTLE }
    data class PedalTracker(val zone: PedalZone, val startY: Float, val startValue: Float)

    private companion object {
        // Background & grid
        const val COLOR_BG          = "#0A0C10"
        const val COLOR_GRID        = "#0D1520"
        const val COLOR_ZONE_BG     = "#10151E"
        const val COLOR_TICK        = "#2A3A4A"
        const val COLOR_ARROW       = "#223344"
        const val COLOR_TITLE       = "#223344"

        // Steering bar
        const val COLOR_STEERING_BG     = "#0E1520"
        const val COLOR_STEERING_BORDER = "#1A2A3A"
        const val COLOR_CENTER_LINE     = "#334455"
        const val COLOR_TEXT_LR         = "#445566"
        const val COLOR_TEXT_STEER      = "#88AACC"
        const val COLOR_STEER_HIGH      = "#FF4400"
        const val COLOR_STEER_MID       = "#FFAA00"
        const val COLOR_STEER_NORMAL    = "#0099FF"

        // Buttons
        const val COLOR_BTN_RECENTER        = "#1A3A5C"
        const val COLOR_BTN_RECENTER_BORDER = "#2255AA"
        const val COLOR_BTN_SENS_DOWN       = "#2A1A1A"
        const val COLOR_BTN_SENS_DOWN_BORDER = "#553322"
        const val COLOR_BTN_SENS_UP         = "#1A2A1A"
        const val COLOR_BTN_SENS_UP_BORDER  = "#225533"

        // Text & labels
        const val COLOR_TEXT        = "#AABBCC"
        const val COLOR_TEXT_STATUS = "#667788"
        const val COLOR_TEXT_SENS   = "#556677"
        const val COLOR_DOT_INACTIVE = "#223344"

        // Pedal zones
        const val COLOR_BRAKE_LABEL    = "#FF6677"
        const val COLOR_THROTTLE_LABEL = "#66FF88"
        const val COLOR_BRAKE_BORDER   = "#551122"
        const val COLOR_THROTTLE_BORDER = "#115522"

        // Pedal gradients (bottom → top)
        const val COLOR_BRAKE_LOW    = "#8B0000"
        const val COLOR_BRAKE_MID    = "#FF2233"
        const val COLOR_BRAKE_HIGH   = "#FF6677"
        const val COLOR_THROTTLE_LOW  = "#003B00"
        const val COLOR_THROTTLE_MID  = "#00CC44"
        const val COLOR_THROTTLE_HIGH = "#66FF88"

        // Status
        const val COLOR_CONNECTED    = "#00CC44"
        const val COLOR_DISCONNECTED = "#CC2200"
    }
}
