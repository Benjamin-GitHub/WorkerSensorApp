package com.example.workersensorapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.core.content.ContextCompat
import com.example.workersensorapp.ui.theme.WorkerSensorAppTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { WorkerSensorAppTheme { SensorApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorApp() {
    val context = LocalContext.current
    val settingsStore = remember { MqttSettingsStore(context.applicationContext) }
    val coroutineScope = rememberCoroutineScope()
    val appState by SensorAppState.state.collectAsState()

    var workerId by remember { mutableStateOf("worker01") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("1883") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var clientId by remember { mutableStateOf("android-worker01-phone") }
    var rememberSettings by remember { mutableStateOf(true) }
    var passwordVisible by remember { mutableStateOf(false) }
    var settingsLoaded by remember { mutableStateOf(false) }
    var validationMessage by remember { mutableStateOf("") }
    var gpsRequested by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val saved = settingsStore.settings.first()
        workerId = saved.workerId
        host = saved.host
        port = saved.port.toString()
        username = saved.username
        password = saved.password
        clientId = saved.clientId
        rememberSettings = saved.rememberSettings
        settingsLoaded = true
    }

    fun currentSettings() = MqttSettings(
        host = host.trim(),
        port = port.toIntOrNull() ?: 0,
        username = username.trim(),
        password = password,
        clientId = clientId.trim(),
        workerId = workerId.trim(),
        rememberSettings = rememberSettings
    )

    fun connect() {
        val settings = currentSettings()
        validationMessage = when {
            settings.host.isBlank() -> "Broker IP is required."
            settings.port !in 1..65535 -> "Port must be between 1 and 65535."
            settings.username.isBlank() -> "Username is required."
            settings.password.isBlank() -> "Password is required."
            settings.clientId.isBlank() -> "Client ID is required."
            settings.workerId.isBlank() -> "Worker ID is required."
            else -> ""
        }
        if (validationMessage.isNotEmpty()) return
        coroutineScope.launch { settingsStore.save(settings) }
        startSensorService(context, settings, gpsRequested)
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { connect() }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        gpsRequested = granted
        validationMessage = if (granted) "" else "Location permission is required for GPS."
        if (appState.isRunning) setGpsEnabled(context, granted)
    }

    fun enableGps() {
        val hasLocationPermission =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
        if (hasLocationPermission) {
            gpsRequested = true
            if (appState.isRunning) setGpsEnabled(context, true)
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    fun disableGps() {
        gpsRequested = false
        if (appState.isRunning) setGpsEnabled(context, false)
    }

    fun requestConnect() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            connect()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Worker Sensor")
                        Text(
                            "Multimodal data node",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    ) {
        innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            StatusCard(appState)

            SectionCard(
                title = "MQTT connection",
                explanation = "Raspberry Pi broker credentials and unique phone/worker identities."
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ConnectionField(
                        host, { host = it }, "Broker IP / host", appState.isRunning,
                        Modifier.weight(2f)
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter(Char::isDigit) },
                        label = { Text("Port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        enabled = !appState.isRunning,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ConnectionField(
                        username, { username = it }, "Username", appState.isRunning,
                        Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = if (passwordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    if (passwordVisible) {
                                        Icons.Default.VisibilityOff
                                    } else {
                                        Icons.Default.Visibility
                                    },
                                    if (passwordVisible) "Hide password" else "Show password"
                                )
                            }
                        },
                        enabled = !appState.isRunning,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ConnectionField(
                        clientId, { clientId = it }, "Unique client ID", appState.isRunning,
                        Modifier.weight(1f)
                    )
                    ConnectionField(
                        workerId, { workerId = it }, "Worker / topic ID", appState.isRunning,
                        Modifier.weight(1f)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = rememberSettings,
                        onCheckedChange = { rememberSettings = it },
                        enabled = !appState.isRunning
                    )
                    Column {
                        Text("Remember settings")
                        Text(
                            "Refills all fields; development password storage is not encrypted.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            GpsCard(
                enabled = if (appState.isRunning) appState.gpsEnabled else gpsRequested,
                reading = appState.reading,
                onEnable = ::enableGps,
                onDisable = ::disableGps
            )

            SectionCard(
                title = "Streaming controls",
                explanation = "Connect: read IMU at 50 Hz and publish combined JSON at 10 Hz."
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = ::requestConnect,
                        enabled = settingsLoaded && !appState.isRunning,
                        modifier = Modifier.weight(1f)
                    ) { Text("Connect") }
                    Button(
                        onClick = {
                            context.startService(
                                Intent(context, SensorMqttService::class.java)
                                    .setAction(SensorMqttService.ACTION_STOP)
                            )
                        },
                        enabled = appState.isRunning,
                        modifier = Modifier.weight(1f)
                    ) { Text("Disconnect") }
                }
                if (validationMessage.isNotEmpty()) {
                    Text(validationMessage, color = MaterialTheme.colorScheme.error)
                }
                HorizontalDivider()
                Text(
                    appState.message,
                    color = if (appState.isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }

            SensorCard(appState.reading)
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    explanation: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                explanation,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            content()
        }
    }
}

@Composable
private fun ConnectionField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    running: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = !running,
        modifier = modifier
    )
}

private fun startSensorService(context: Context, settings: MqttSettings, gpsEnabled: Boolean) {
    val intent = Intent(context, SensorMqttService::class.java)
        .setAction(SensorMqttService.ACTION_START)
        .putExtra(SensorMqttService.EXTRA_WORKER_ID, settings.workerId)
        .putExtra(SensorMqttService.EXTRA_HOST, settings.host)
        .putExtra(SensorMqttService.EXTRA_PORT, settings.port)
        .putExtra(SensorMqttService.EXTRA_MQTT_USERNAME, settings.username)
        .putExtra(SensorMqttService.EXTRA_MQTT_PASSWORD, settings.password)
        .putExtra(SensorMqttService.EXTRA_CLIENT_ID, settings.clientId)
        .putExtra(SensorMqttService.EXTRA_GPS_ENABLED, gpsEnabled)
    ContextCompat.startForegroundService(context, intent)
}

private fun setGpsEnabled(context: Context, enabled: Boolean) {
    context.startService(
        Intent(context, SensorMqttService::class.java)
            .setAction(SensorMqttService.ACTION_SET_GPS)
            .putExtra(SensorMqttService.EXTRA_GPS_ENABLED, enabled)
    )
}

@Composable
private fun GpsCard(
    enabled: Boolean,
    reading: SensorReading,
    onEnable: () -> Unit,
    onDisable: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Optional GPS · ${if (enabled) "On" else "Off"}",
                style = MaterialTheme.typography.titleSmall)
            Text(
                "Adds coordinates, accuracy and time to JSON; off saves battery.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (enabled && reading.latitude != null && reading.longitude != null) {
                Text(
                    String.format(
                        Locale.US,
                        "%.6f, %.6f  (±%.1f m)",
                        reading.latitude,
                        reading.longitude,
                        reading.locationAccuracy ?: 0f
                    ),
                    fontFamily = FontFamily.Monospace
                )
            } else if (enabled) {
                Text("Waiting for a location fix…")
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(onClick = onEnable, enabled = !enabled, modifier = Modifier.weight(1f)) {
                    Text("GPS On")
                }
                Button(onClick = onDisable, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Text("GPS Off")
                }
            }
        }
    }
}

@Composable
private fun StatusCard(state: SensorUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (state.isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            }
        )
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Status: ${state.connectionStatus}", style = MaterialTheme.typography.titleMedium)
                Text(
                    "IMU 50 Hz · MQTT 10 packets/s",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Text("${state.packetsSent} sent", style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun SensorCard(reading: SensorReading) {
    fun value(number: Float) = String.format(Locale.US, "% .3f", number)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Live IMU telemetry", style = MaterialTheme.typography.titleSmall)
            Text(
                "Acceleration m/s² · rotation rad/s",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "AX ${value(reading.ax)}   AY ${value(reading.ay)}   AZ ${value(reading.az)}",
                fontFamily = FontFamily.Monospace
            )
            Text(
                "GX ${value(reading.gx)}   GY ${value(reading.gy)}   GZ ${value(reading.gz)}",
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
