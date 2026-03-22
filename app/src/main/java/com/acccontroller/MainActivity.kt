package com.acccontroller

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var gyroSensor: Sensor? = null

    private val steeringAngle = AtomicReference(0f)
    private var sensitivityLevel = 3

    @Volatile private var throttleValue = 0f
    @Volatile private var brakeValue = 0f

    private val activityScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var socket: java.net.Socket? = null
    private var writer: java.io.PrintWriter? = null
    private val serverPort = 9999
    private val serverHost = "192.168.1.32"

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var controllerView: ControllerView

    private var lastSensChangeMs = 0L
    private var firstSensorEvent = true

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.i(TAG, "onCreate: START")

        // Catch crashes on any background thread and log them
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "UNCAUGHT EXCEPTION on thread '${thread.name}': ${throwable.message}", throwable)
        }

        try {
            super.onCreate(savedInstanceState)
            Log.d(TAG, "onCreate: [1] super.onCreate done")

            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
            Log.d(TAG, "onCreate: [2] window flags set")

            Log.d(TAG, "onCreate: [3] creating ControllerView...")
            controllerView = ControllerView(this)
            Log.d(TAG, "onCreate: [4] ControllerView created OK")

            setContentView(controllerView)
            Log.d(TAG, "onCreate: [5] setContentView done")

            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            Log.d(TAG, "onCreate: [6] SensorManager obtained")

            gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            Log.i(TAG, "onCreate: [7] gyroSensor = ${if (gyroSensor != null) gyroSensor!!.name else "NULL — device has no gyroscope!"}")

            controllerView.onThrottleChanged = { v -> throttleValue = v }
            controllerView.onBrakeChanged    = { v -> brakeValue    = v }
            controllerView.onRecenter        = { steeringAngle.set(0f); Log.d(TAG, "recenter triggered") }
            Log.d(TAG, "onCreate: [8] callbacks set")

            controllerView.onSensitivityUp = {
                val now = System.currentTimeMillis()
                if (sensitivityLevel < 5 && now - lastSensChangeMs > SENS_DEBOUNCE_MS) {
                    lastSensChangeMs = now
                    sensitivityLevel++
                    controllerView.sensitivityLevel = sensitivityLevel
                    controllerView.invalidate()
                    Log.d(TAG, "sensitivity UP → $sensitivityLevel")
                }
            }
            controllerView.onSensitivityDown = {
                val now = System.currentTimeMillis()
                if (sensitivityLevel > 1 && now - lastSensChangeMs > SENS_DEBOUNCE_MS) {
                    lastSensChangeMs = now
                    sensitivityLevel--
                    controllerView.sensitivityLevel = sensitivityLevel
                    controllerView.invalidate()
                    Log.d(TAG, "sensitivity DOWN → $sensitivityLevel")
                }
            }

            Log.d(TAG, "onCreate: [9] starting network loop (host=$serverHost port=$serverPort)")
            startNetworkLoop()

            Log.d(TAG, "onCreate: [10] starting send loop")
            startSendLoop()

            Log.i(TAG, "onCreate: COMPLETE ✓")

        } catch (e: Exception) {
            Log.e(TAG, "onCreate: CRASH at some step — ${e.javaClass.simpleName}: ${e.message}", e)
            throw e  // re-throw so Android still shows the crash dialog
        }
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "onResume: registering gyro listener (sensor=${gyroSensor?.name ?: "null"})")
        steeringAngle.set(0f)
        gyroSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
            Log.d(TAG, "onResume: gyro listener registered")
        } ?: Log.w(TAG, "onResume: no gyroscope — steering will not work")
    }

    override fun onPause() {
        super.onPause()
        Log.i(TAG, "onPause: unregistering sensor listener")
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy: cancelling scope, closing socket")
        activityScope.cancel()
        socket?.close()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            if (firstSensorEvent) {
                Log.d(TAG, "onSensorChanged: first gyro event received — sensor is working")
                firstSensorEvent = false
            }

            val rotZ = -event.values[2]
            val sensitivity = SENSITIVITY_LEVELS[sensitivityLevel - 1]

            val updated = steeringAngle.updateAndGet { current ->
                var v = (current + rotZ * SENSOR_TIMESTEP_S * sensitivity).coerceIn(-1f, 1f)
                if (abs(rotZ) < GYRO_CENTER_THRESHOLD) v * GYRO_CENTER_DECAY else v
            }

            val s = if (abs(updated) < STEERING_DEADZONE) 0f else updated
            mainHandler.post { controllerView.updateSteering(s) }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun startNetworkLoop() {
        activityScope.launch {
            Log.d(TAG, "networkLoop: coroutine started")
            while (isActive) {
                try {
                    Log.d(TAG, "networkLoop: connecting to $serverHost:$serverPort ...")
                    val s = java.net.Socket()
                    s.connect(java.net.InetSocketAddress(serverHost, serverPort), 3000)
                    s.tcpNoDelay = true
                    s.setSendBufferSize(1024)
                    socket = s
                    writer = java.io.PrintWriter(s.getOutputStream(), true)
                    Log.i(TAG, "networkLoop: CONNECTED to $serverHost:$serverPort")
                    mainHandler.post { controllerView.setConnected(true) }
                    while (isActive && !s.isClosed && s.isConnected) {
                        delay(200)
                    }
                    Log.w(TAG, "networkLoop: socket closed — will reconnect")
                } catch (e: Exception) {
                    Log.w(TAG, "networkLoop: connection failed — ${e.javaClass.simpleName}: ${e.message}")
                    mainHandler.post { controllerView.setConnected(false) }
                    delay(1500)
                } finally {
                    writer = null
                    socket?.close()
                    socket = null
                }
            }
            Log.d(TAG, "networkLoop: coroutine ended")
        }
    }

    private fun startSendLoop() {
        activityScope.launch {
            Log.d(TAG, "sendLoop: coroutine started")
            var sendCount = 0L
            while (isActive) {
                try {
                    val steer    = String.format("%.4f", steeringAngle.get())
                    val throttle = String.format("%.4f", throttleValue)
                    val brake    = String.format("%.4f", brakeValue)
                    writer?.println("S:$steer,T:$throttle,B:$brake")

                    // Log first successful send, then every 500 sends (~4 sec)
                    sendCount++
                    if (sendCount == 1L || sendCount % 500 == 0L) {
                        Log.d(TAG, "sendLoop: #$sendCount → S:$steer T:$throttle B:$brake")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "sendLoop: send failed — ${e.javaClass.simpleName}: ${e.message}", e)
                }
                delay(8)
            }
            Log.d(TAG, "sendLoop: coroutine ended")
        }
    }

    private companion object {
        const val TAG = "AccController"

        const val SENSOR_TIMESTEP_S     = 0.008f
        const val GYRO_CENTER_THRESHOLD = 0.04f
        const val GYRO_CENTER_DECAY     = 0.93f
        const val STEERING_DEADZONE     = 0.02f

        val SENSITIVITY_LEVELS = floatArrayOf(1.0f, 1.8f, 2.5f, 3.5f, 5.0f)

        const val SENS_DEBOUNCE_MS = 150L
    }
}
