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

    // Steering — AtomicReference for thread-safe cross-thread access (sensor → IO send loop)
    private val steeringAngle = AtomicReference(0f)
    private var sensitivityLevel = 3  // 1-5

    // Pedals — @Volatile for visibility across main/IO threads
    @Volatile private var throttleValue = 0f
    @Volatile private var brakeValue = 0f

    // Network
    private val activityScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var socket: java.net.Socket? = null
    private var writer: java.io.PrintWriter? = null
    private val serverPort = 9999
    private val serverHost = "192.168.1.32"

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var controllerView: ControllerView

    // Debounce sensitivity button taps
    private var lastSensChangeMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        controllerView = ControllerView(this)
        setContentView(controllerView)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        controllerView.onThrottleChanged = { v -> throttleValue = v }
        controllerView.onBrakeChanged    = { v -> brakeValue    = v }
        controllerView.onRecenter        = { steeringAngle.set(0f) }

        controllerView.onSensitivityUp = {
            val now = System.currentTimeMillis()
            if (sensitivityLevel < 5 && now - lastSensChangeMs > SENS_DEBOUNCE_MS) {
                lastSensChangeMs = now
                sensitivityLevel++
                controllerView.sensitivityLevel = sensitivityLevel
                controllerView.invalidate()
            }
        }

        controllerView.onSensitivityDown = {
            val now = System.currentTimeMillis()
            if (sensitivityLevel > 1 && now - lastSensChangeMs > SENS_DEBOUNCE_MS) {
                lastSensChangeMs = now
                sensitivityLevel--
                controllerView.sensitivityLevel = sensitivityLevel
                controllerView.invalidate()
            }
        }

        startNetworkLoop()
        startSendLoop()
    }

    override fun onResume() {
        super.onResume()
        steeringAngle.set(0f)
        gyroSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()   // cancels both networkJob and sendJob
        socket?.close()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            val rotZ = -event.values[2]
            val sensitivity = SENSITIVITY_LEVELS[sensitivityLevel - 1]

            // Atomic read-modify-write: safe to read from IO thread concurrently
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
            while (isActive) {
                try {
                    val s = java.net.Socket()
                    s.connect(java.net.InetSocketAddress(serverHost, serverPort), 3000)
                    s.tcpNoDelay = true
                    s.setSendBufferSize(1024)
                    socket = s
                    writer = java.io.PrintWriter(s.getOutputStream(), true)
                    mainHandler.post { controllerView.setConnected(true) }
                    while (isActive && !s.isClosed && s.isConnected) {
                        delay(200)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Network error: ${e.message}")
                    mainHandler.post { controllerView.setConnected(false) }
                    delay(1500)
                } finally {
                    // Always clean up on disconnect or error before retrying
                    writer = null
                    socket?.close()
                    socket = null
                }
            }
        }
    }

    private fun startSendLoop() {
        activityScope.launch {
            while (isActive) {
                try {
                    val steer    = String.format("%.4f", steeringAngle.get())
                    val throttle = String.format("%.4f", throttleValue)
                    val brake    = String.format("%.4f", brakeValue)
                    writer?.println("S:$steer,T:$throttle,B:$brake")
                } catch (e: Exception) {
                    Log.e(TAG, "Send failed: ${e.message}", e)
                }
                delay(8)   // ~120Hz send rate
            }
        }
    }

    private companion object {
        const val TAG = "AccController"

        // Sensor processing
        const val SENSOR_TIMESTEP_S    = 0.008f  // fixed timestep matching SENSOR_DELAY_FASTEST ~8ms
        const val GYRO_CENTER_THRESHOLD = 0.04f  // rad/s — below this, auto-center steering
        const val GYRO_CENTER_DECAY    = 0.93f   // exponential decay per frame (~50% damping at 11 frames)
        const val STEERING_DEADZONE    = 0.02f   // minimum input to register

        val SENSITIVITY_LEVELS = floatArrayOf(1.0f, 1.8f, 2.5f, 3.5f, 5.0f)

        // UI
        const val SENS_DEBOUNCE_MS = 150L        // minimum ms between sensitivity button taps
    }
}
