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

    private val bgPaint = Paint().apply { color = Color.parseColor("#0A0C10"); style = Paint.Style.FILL }
    private val zoneBgPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val zoneBorderPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f; isAntiAlias = true }
    private val barFillPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val textPaint = Paint().apply {
        color = Color.parseColor("#AABBCC"); textSize = 36f
        typeface = Typeface.create("monospace", Typeface.BOLD)
        textAlign = Paint.Align.CENTER; isAntiAlias = true
    }
    private val bigTextPaint = Paint().apply {
        color = Color.WHITE; textSize = 52f
        typeface = Typeface.create("monospace", Typeface.BOLD)
        textAlign = Paint.Align.CENTER; isAntiAlias = true
    }
    private val btnPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
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
        color = Color.parseColor("#334455"); style = Paint.Style.STROKE
        strokeWidth = 1f; pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }

    private var brakeGradient: LinearGradient? = null
    private var throttleGradient: LinearGradient? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val padding = 24f
        val zoneW = w * 0.22f
        val steerH = 70f
        val steerY = (h - steerH) / 2f

        brakeZone = RectF(padding, padding, zoneW, h - padding)
        throttleZone = RectF(w - zoneW, padding, w - padding, h - padding)
        steeringBarRect = RectF(zoneW + 40f, steerY, w - zoneW - 40f, steerY + steerH)

        // Buttons below steering bar
        val btnY = steerY + steerH + 20f
        val btnW = 120f
        val btnH = 52f
        val cx = w / 2f
        recenterBtn  = RectF(cx - btnW/2, btnY, cx + btnW/2, btnY + btnH)
        sensDownBtn  = RectF(cx - btnW*1.8f, btnY, cx - btnW*0.8f, btnY + btnH)
        sensUpBtn    = RectF(cx + btnW*0.8f, btnY, cx + btnW*1.8f, btnY + btnH)

        brakeGradient = LinearGradient(
            brakeZone.left, brakeZone.bottom, brakeZone.left, brakeZone.top,
            intArrayOf(Color.parseColor("#8B0000"), Color.parseColor("#FF2233"), Color.parseColor("#FF6677")),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
        )
        throttleGradient = LinearGradient(
            throttleZone.left, throttleZone.bottom, throttleZone.left, throttleZone.top,
            intArrayOf(Color.parseColor("#003B00"), Color.parseColor("#00CC44"), Color.parseColor("#66FF88")),
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
        val p = Paint().apply { color = Color.parseColor("#0D1520"); style = Paint.Style.STROKE; strokeWidth = 1f }
        val step = 60f
        var x = 0f; while (x < w) { canvas.drawLine(x, 0f, x, h, p); x += step }
        var y = 0f; while (y < h) { canvas.drawLine(0f, y, w, y, p); y += step }
    }

    private fun drawPedalZone(canvas: Canvas, zone: RectF, value: Float, isBrake: Boolean) {
        val r = 20f
        zoneBgPaint.color = Color.parseColor("#10151E")
        canvas.drawRoundRect(zone, r, r, zoneBgPaint)

        val fillH = zone.height() * value
        if (fillH > 8f) {
            val fillRect = RectF(zone.left + 4, zone.bottom - fillH, zone.right - 4, zone.bottom - 4)
            barFillPaint.shader = if (isBrake) brakeGradient else throttleGradient
            canvas.drawRoundRect(fillRect, 12f, 12f, barFillPaint)
            barFillPaint.shader = null
        }

        val tickPaint = Paint().apply { color = Color.parseColor("#2A3A4A"); style = Paint.Style.STROKE; strokeWidth = 1.5f }
        for (i in 1..9) {
            val ty = zone.top + zone.height() * (i / 10f)
            canvas.drawLine(zone.left + 12, ty, zone.right - 12, ty, tickPaint)
        }

        zoneBorderPaint.color = if (isBrake) Color.parseColor("#551122") else Color.parseColor("#115522")
        canvas.drawRoundRect(zone, r, r, zoneBorderPaint)

        val label = if (isBrake) "BRAKE" else "THROTTLE"
        textPaint.color = if (isBrake) Color.parseColor("#FF6677") else Color.parseColor("#66FF88")
        textPaint.textSize = 28f
        canvas.drawText(label, zone.centerX(), zone.top + 42f, textPaint)
        bigTextPaint.textSize = 50f
        canvas.drawText("${(value * 100).toInt()}%", zone.centerX(), zone.bottom - 26f, bigTextPaint)

        if (value < 0.02f) {
            val ap = Paint().apply { color = Color.parseColor("#223344"); style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true }
            val cx = zone.centerX(); val my = zone.centerY()
            canvas.drawLine(cx, my - 40f, cx, my + 40f, ap)
            canvas.drawLine(cx, my + 40f, cx - 18f, my + 16f, ap)
            canvas.drawLine(cx, my + 40f, cx + 18f, my + 16f, ap)
        }
    }

    private fun drawSteeringBar(canvas: Canvas, w: Float, h: Float) {
        val bar = steeringBarRect
        val cx = bar.centerX()
        val r = bar.height() / 2f

        val bgP = Paint().apply { color = Color.parseColor("#0E1520"); style = Paint.Style.FILL }
        canvas.drawRoundRect(bar, r, r, bgP)

        val halfW = bar.width() / 2f
        if (abs(steeringValue) > 0.02f) {
            val fillEnd = cx + steeringValue * halfW
            val fillRect = if (steeringValue >= 0f)
                RectF(cx, bar.top + 4, fillEnd, bar.bottom - 4)
            else
                RectF(fillEnd, bar.top + 4, cx, bar.bottom - 4)
            barFillPaint.color = when {
                abs(steeringValue) > 0.8f -> Color.parseColor("#FF4400")
                abs(steeringValue) > 0.5f -> Color.parseColor("#FFAA00")
                else -> Color.parseColor("#0099FF")
            }
            canvas.drawRect(fillRect, barFillPaint)
        }

        canvas.drawLine(cx, bar.top - 8, cx, bar.bottom + 8, centerLinePaint)

        val needleX = cx + steeringValue * halfW
        val nP = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL; isAntiAlias = true }
        canvas.drawRoundRect(RectF(needleX - 4f, bar.top - 8, needleX + 4f, bar.bottom + 8), 4f, 4f, nP)

        val bP = Paint().apply { color = Color.parseColor("#1A2A3A"); style = Paint.Style.STROKE; strokeWidth = 1.5f }
        canvas.drawRoundRect(bar, r, r, bP)

        textPaint.color = Color.parseColor("#445566"); textPaint.textSize = 24f
        canvas.drawText("L", bar.left + 28f, bar.centerY() + 9f, textPaint)
        canvas.drawText("R", bar.right - 28f, bar.centerY() + 9f, textPaint)

        textPaint.color = Color.parseColor("#88AACC"); textPaint.textSize = 26f
        val dir = if (steeringValue > 0.02f) "R" else if (steeringValue < -0.02f) "L" else ""
        canvas.drawText("STEER  ${(abs(steeringValue) * 100).toInt()}% $dir", cx, bar.top - 18f, textPaint)
    }

    private fun drawButtons(canvas: Canvas) {
        // Recenter button
        btnPaint.color = Color.parseColor("#1A3A5C")
        canvas.drawRoundRect(recenterBtn, 12f, 12f, btnPaint)
        val borderP = Paint().apply { color = Color.parseColor("#2255AA"); style = Paint.Style.STROKE; strokeWidth = 1.5f; isAntiAlias = true }
        canvas.drawRoundRect(recenterBtn, 12f, 12f, borderP)
        btnTextPaint.textSize = 22f
        canvas.drawText("⊕ CENTER", recenterBtn.centerX(), recenterBtn.centerY() + 8f, btnTextPaint)

        // Sensitivity down
        btnPaint.color = Color.parseColor("#2A1A1A")
        canvas.drawRoundRect(sensDownBtn, 12f, 12f, btnPaint)
        val brd2 = Paint().apply { color = Color.parseColor("#553322"); style = Paint.Style.STROKE; strokeWidth = 1.5f; isAntiAlias = true }
        canvas.drawRoundRect(sensDownBtn, 12f, 12f, brd2)
        btnTextPaint.textSize = 22f
        canvas.drawText("◀ SENS", sensDownBtn.centerX(), sensDownBtn.centerY() + 8f, btnTextPaint)

        // Sensitivity up
        btnPaint.color = Color.parseColor("#1A2A1A")
        canvas.drawRoundRect(sensUpBtn, 12f, 12f, btnPaint)
        val brd3 = Paint().apply { color = Color.parseColor("#225533"); style = Paint.Style.STROKE; strokeWidth = 1.5f; isAntiAlias = true }
        canvas.drawRoundRect(sensUpBtn, 12f, 12f, brd3)
        canvas.drawText("SENS ▶", sensUpBtn.centerX(), sensUpBtn.centerY() + 8f, btnTextPaint)

        // Sensitivity level dots
        val cx = width / 2f
        val dotY = sensDownBtn.bottom + 22f
        for (i in 1..5) {
            val dotX = cx + (i - 3) * 22f
            val dotP = Paint().apply {
                color = if (i <= sensitivityLevel) Color.parseColor("#00CC44") else Color.parseColor("#223344")
                style = Paint.Style.FILL; isAntiAlias = true
            }
            canvas.drawCircle(dotX, dotY, 7f, dotP)
        }
        textPaint.color = Color.parseColor("#556677"); textPaint.textSize = 20f
        canvas.drawText("SENSITIVITY", cx, dotY + 22f, textPaint)
    }

    private fun drawStatus(canvas: Canvas, w: Float, h: Float) {
        val dotX = w - 40f; val dotY = 28f
        connectedPaint.color = if (isConnected) Color.parseColor("#00CC44") else Color.parseColor("#CC2200")
        canvas.drawCircle(dotX, dotY, 8f, connectedPaint)
        connectedPaint.color = Color.parseColor("#667788")
        connectedPaint.textSize = 22f
        (connectedPaint as Paint).textAlign = Paint.Align.RIGHT
        canvas.drawText(if (isConnected) "CONNECTED" else "CONNECTING...", dotX - 18f, dotY + 7f, connectedPaint)
        textPaint.color = Color.parseColor("#223344"); textPaint.textSize = 22f
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

                // Check buttons first
                if (recenterBtn.contains(x, y)) { onRecenter?.invoke(); invalidate(); return true }
                if (sensUpBtn.contains(x, y)) { onSensitivityUp?.invoke(); invalidate(); return true }
                if (sensDownBtn.contains(x, y)) { onSensitivityDown?.invoke(); invalidate(); return true }

                val zone = when {
                    brakeZone.contains(x, y) -> PedalZone.BRAKE
                    throttleZone.contains(x, y) -> PedalZone.THROTTLE
                    else -> null
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
                        PedalZone.BRAKE -> { brakeValue = newValue; onBrakeChanged?.invoke(brakeValue) }
                        PedalZone.THROTTLE -> { throttleValue = newValue; onThrottleChanged?.invoke(throttleValue) }
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val tracker = activePointers.remove(pId)
                if (tracker != null) {
                    when (tracker.zone) {
                        PedalZone.BRAKE -> { brakeValue = 0f; onBrakeChanged?.invoke(0f) }
                        PedalZone.THROTTLE -> { throttleValue = 0f; onThrottleChanged?.invoke(0f) }
                    }
                    invalidate()
                }
            }
        }
        return true
    }

    private fun getCurrentValue(zone: PedalZone) = when (zone) {
        PedalZone.BRAKE -> brakeValue
        PedalZone.THROTTLE -> throttleValue
    }

    fun updateSteering(value: Float) { steeringValue = value; invalidate() }
    fun setConnected(connected: Boolean) { isConnected = connected; invalidate() }

    enum class PedalZone { BRAKE, THROTTLE }
    data class PedalTracker(val zone: PedalZone, val startY: Float, val startValue: Float)
}