package com.acccontroller

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import kotlin.math.abs

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var gyroSensor: Sensor? = null

    // Steering
    private var steeringAngle = 0f
    private var sensitivityLevel = 3  // 1-5
    private val sensitivityValues = floatArrayOf(1.0f, 1.8f, 2.5f, 3.5f, 5.0f)
    private val steeringDeadzone = 0.02f

    // Pedals
    private var throttleValue = 0f
    private var brakeValue = 0f

    // Network
    private var networkJob: Job? = null
    private var sendJob: Job? = null
    private var socket: java.net.Socket? = null
    private var writer: java.io.PrintWriter? = null
    private val serverPort = 9999
    private val serverHost = "192.168.1.32"

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var controllerView: ControllerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        controllerView.onBrakeChanged = { v -> brakeValue = v }

        controllerView.onRecenter = {
            steeringAngle = 0f
        }

        controllerView.onSensitivityUp = {
            if (sensitivityLevel < 5) {
                sensitivityLevel++
                controllerView.sensitivityLevel = sensitivityLevel
                controllerView.invalidate()
            }
        }

        controllerView.onSensitivityDown = {
            if (sensitivityLevel > 1) {
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
        steeringAngle = 0f
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
        sendJob?.cancel()
        networkJob?.cancel()
        socket?.close()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            val rotZ = -event.values[2]
            val sensitivity = sensitivityValues[sensitivityLevel - 1]

            // Use SENSOR_DELAY_FASTEST so delta is smaller — use fixed 8ms timestep
            steeringAngle += rotZ * 0.008f * sensitivity
            steeringAngle = steeringAngle.coerceIn(-1f, 1f)

            // Auto-center when still
            if (abs(rotZ) < 0.04f) {
                steeringAngle *= 0.93f
            }

            val s = if (abs(steeringAngle) < steeringDeadzone) 0f else steeringAngle

            mainHandler.post {
                controllerView.updateSteering(s)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun startNetworkLoop() {
        networkJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    val s = java.net.Socket()
                    s.connect(java.net.InetSocketAddress(serverHost, serverPort), 3000)
                    s.tcpNoDelay = true           // disable Nagle — reduces latency
                    s.setSendBufferSize(1024)
                    socket = s
                    writer = java.io.PrintWriter(s.getOutputStream(), true)
                    mainHandler.post { controllerView.setConnected(true) }
                    while (isActive && !s.isClosed && s.isConnected) {
                        delay(200)
                    }
                } catch (e: Exception) {
                    mainHandler.post { controllerView.setConnected(false) }
                    delay(1500)
                }
            }
        }
    }

    private fun startSendLoop() {
        sendJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    val steer    = String.format("%.4f", steeringAngle)
                    val throttle = String.format("%.4f", throttleValue)
                    val brake    = String.format("%.4f", brakeValue)
                    writer?.println("S:$steer,T:$throttle,B:$brake")
                } catch (e: Exception) { }
                delay(8)   // 120Hz send rate
            }
        }
    }
}