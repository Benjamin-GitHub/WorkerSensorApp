package com.example.workersensorapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class SensorMqttService : Service(), SensorEventListener, LocationListener {
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private var mqttClient: MqttAsyncClient? = null
    private var scheduler: ScheduledExecutorService? = null
    private var workerId = ""
    private var host = ""
    private var port = 1883
    private var mqttUsername = ""
    private var mqttPassword = ""
    private var clientId = ""

    @Volatile private var latestReading = SensorReading()
    @Volatile private var running = false
    @Volatile private var gpsEnabled = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopCollection()
            ACTION_SET_GPS -> setGpsEnabled(
                intent.getBooleanExtra(EXTRA_GPS_ENABLED, false)
            )
            ACTION_START -> {
                if (!running) {
                    workerId = intent.getStringExtra(EXTRA_WORKER_ID).orEmpty()
                    host = intent.getStringExtra(EXTRA_HOST).orEmpty()
                    port = intent.getIntExtra(EXTRA_PORT, 1883)
                    mqttUsername = intent.getStringExtra(EXTRA_MQTT_USERNAME).orEmpty()
                    mqttPassword = intent.getStringExtra(EXTRA_MQTT_PASSWORD).orEmpty()
                    clientId = intent.getStringExtra(EXTRA_CLIENT_ID).orEmpty()
                    val startWithGps = intent.getBooleanExtra(EXTRA_GPS_ENABLED, false)
                    if (
                        workerId.isBlank() ||
                        host.isBlank() ||
                        port !in 1..65535 ||
                        clientId.isBlank()
                    ) {
                        reportError("Valid worker, broker, port, and client ID values are required.")
                        stopSelf()
                    } else {
                        startForegroundWithTypes(location = false)
                        startCollection()
                        if (startWithGps) setGpsEnabled(true)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startCollection() {
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (accelerometer == null || gyroscope == null) {
            reportError("This device does not provide both required sensors.")
            stopSelf()
            return
        }

        running = true
        SensorAppState.update {
            it.copy(
                isRunning = true,
                connectionStatus = "Connecting",
                packetsSent = 0,
                message = "Connecting to $host:$port",
                isError = false
            )
        }
        sensorManager.registerListener(this, accelerometer, SENSOR_PERIOD_US)
        sensorManager.registerListener(this, gyroscope, SENSOR_PERIOD_US)
        connectMqtt()

        scheduler = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleWithFixedDelay(
                ::publishLatestReading,
                100,
                PUBLISH_PERIOD_MS,
                TimeUnit.MILLISECONDS
            )
        }
    }

    private fun connectMqtt() {
        try {
            val brokerUri = "tcp://$host:$port"
            val client = MqttAsyncClient(brokerUri, clientId, null)
            mqttClient = client
            client.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    SensorAppState.update {
                        it.copy(
                            connectionStatus = "Connected",
                            message = if (reconnect) "MQTT connection restored." else "Publishing sensor data.",
                            isError = false
                        )
                    }
                    updateNotification("Connected · ${SensorAppState.state.value.packetsSent} packets")
                }

                override fun connectionLost(cause: Throwable?) {
                    SensorAppState.update {
                        it.copy(
                            connectionStatus = "Reconnecting",
                            message = cause?.message ?: "MQTT connection lost.",
                            isError = true
                        )
                    }
                    updateNotification("Reconnecting to MQTT")
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) = Unit
                override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
            })
            val options = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = true
                connectionTimeout = 10
                keepAliveInterval = 20
                if (mqttUsername.isNotBlank()) {
                    userName = mqttUsername
                    password = mqttPassword.toCharArray()
                }
            }
            client.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) = Unit

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    val mqttReason = (exception as? MqttException)?.reasonCode
                    val reason = if (
                        mqttReason == MqttException.REASON_CODE_FAILED_AUTHENTICATION.toInt() ||
                        mqttReason == MqttException.REASON_CODE_NOT_AUTHORIZED.toInt()
                    ) "Authentication failed" else "Broker unavailable"
                    SensorAppState.update {
                        it.copy(
                            connectionStatus = reason,
                            message = exception?.message ?: "Could not connect to $host:$port",
                            isError = true
                        )
                    }
                    updateNotification(reason)
                }
            })
        } catch (error: Exception) {
            reportError("MQTT setup failed: ${error.message}")
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = System.currentTimeMillis()
        latestReading = when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> latestReading.copy(
                ax = event.values[0],
                ay = event.values[1],
                az = event.values[2],
                timestamp = now
            )
            Sensor.TYPE_GYROSCOPE -> latestReading.copy(
                gx = event.values[0],
                gy = event.values[1],
                gz = event.values[2],
                timestamp = now
            )
            else -> latestReading
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onLocationChanged(location: Location) {
        latestReading = latestReading.copy(
            latitude = location.latitude,
            longitude = location.longitude,
            locationAccuracy = location.accuracy,
            locationTimestamp = location.time
        )
        SensorAppState.update { it.copy(reading = latestReading) }
    }

    private fun setGpsEnabled(enabled: Boolean) {
        if (!running) return
        if (!enabled) {
            locationManager.removeUpdates(this)
            gpsEnabled = false
            latestReading = latestReading.copy(
                latitude = null,
                longitude = null,
                locationAccuracy = null,
                locationTimestamp = null
            )
            SensorAppState.update {
                it.copy(gpsEnabled = false, reading = latestReading, message = "GPS disabled.")
            }
            startForegroundWithTypes(location = false)
            return
        }

        val fineGranted = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) {
            SensorAppState.update {
                it.copy(message = "Location permission is required for GPS.", isError = true)
            }
            return
        }
        try {
            startForegroundWithTypes(location = true)
            val provider = if (fineGranted) {
                LocationManager.GPS_PROVIDER
            } else {
                LocationManager.NETWORK_PROVIDER
            }
            locationManager.requestLocationUpdates(provider, GPS_PERIOD_MS, 0f, this)
            gpsEnabled = true
            SensorAppState.update {
                it.copy(gpsEnabled = true, message = "GPS enabled; waiting for a location fix.")
            }
        } catch (error: Exception) {
            gpsEnabled = false
            SensorAppState.update {
                it.copy(message = "Could not enable GPS: ${error.message}", isError = true)
            }
        }
    }

    private fun publishLatestReading() {
        val reading = latestReading
        SensorAppState.update { it.copy(reading = reading) }
        val client = mqttClient ?: return
        if (!client.isConnected || reading.timestamp == 0L) return

        val payload = JSONObject()
            .put("workerID", workerId)
            .put("timestamp", reading.timestamp)
            .put("ax", reading.ax.toDouble())
            .put("ay", reading.ay.toDouble())
            .put("az", reading.az.toDouble())
            .put("gx", reading.gx.toDouble())
            .put("gy", reading.gy.toDouble())
            .put("gz", reading.gz.toDouble())
            .put("gpsEnabled", gpsEnabled)
            .apply {
                if (gpsEnabled && reading.latitude != null && reading.longitude != null) {
                    put("latitude", reading.latitude)
                    put("longitude", reading.longitude)
                    put("locationAccuracy", reading.locationAccuracy)
                    put("locationTimestamp", reading.locationTimestamp)
                }
            }
            .toString()

        try {
            client.publish(
                "factory/workers/${workerId.toTopicSegment()}",
                MqttMessage(payload.toByteArray()).apply { qos = 1 }
            )
            SensorAppState.update { state ->
                val count = state.packetsSent + 1
                if (count % 10L == 0L) updateNotification("Connected · $count packets")
                state.copy(packetsSent = count, isError = false)
            }
        } catch (error: Exception) {
            SensorAppState.update {
                it.copy(message = "Publish failed: ${error.message}", isError = true)
            }
        }
    }

    private fun stopCollection() {
        running = false
        sensorManager.unregisterListener(this)
        locationManager.removeUpdates(this)
        gpsEnabled = false
        scheduler?.shutdownNow()
        scheduler = null
        try {
            mqttClient?.takeIf { it.isConnected }?.disconnect()
            mqttClient?.close()
        } catch (_: Exception) {
            // The service is stopping; no recovery is needed.
        }
        mqttClient = null
        SensorAppState.update {
            it.copy(
                isRunning = false,
                connectionStatus = "Disconnected",
                gpsEnabled = false,
                message = "Sensor streaming stopped.",
                isError = false
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun reportError(message: String) {
        SensorAppState.update {
            it.copy(
                isRunning = false,
                connectionStatus = "Error",
                message = message,
                isError = true
            )
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Sensor streaming",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(content: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Worker Sensor App")
            .setContentText(content)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(content: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(content))
    }

    private fun startForegroundWithTypes(location: Boolean) {
        val notification = buildNotification(
            if (location) "MQTT sensor streaming · GPS on" else "Connecting to MQTT"
        )
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        if (location) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        startForeground(NOTIFICATION_ID, notification, types)
    }

    override fun onDestroy() {
        if (running) stopCollection()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun String.toTopicSegment(): String =
        lowercase().replace(Regex("[^a-z0-9_-]"), "_")

    companion object {
        const val ACTION_START = "com.example.workersensorapp.START"
        const val ACTION_STOP = "com.example.workersensorapp.STOP"
        const val ACTION_SET_GPS = "com.example.workersensorapp.SET_GPS"
        const val EXTRA_WORKER_ID = "worker_id"
        const val EXTRA_HOST = "mqtt_host"
        const val EXTRA_PORT = "mqtt_port"
        const val EXTRA_MQTT_USERNAME = "mqtt_username"
        const val EXTRA_MQTT_PASSWORD = "mqtt_password"
        const val EXTRA_CLIENT_ID = "mqtt_client_id"
        const val EXTRA_GPS_ENABLED = "gps_enabled"

        private const val SENSOR_PERIOD_US = 20_000
        private const val PUBLISH_PERIOD_MS = 100L
        private const val GPS_PERIOD_MS = 1_000L
        private const val NOTIFICATION_CHANNEL_ID = "sensor_stream"
        private const val NOTIFICATION_ID = 1001
    }
}
