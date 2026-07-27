package com.example.workersensorapp

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.mqttDataStore by preferencesDataStore(name = "mqtt_settings")

data class MqttSettings(
    val host: String = "",
    val port: Int = 1883,
    val username: String = "",
    val password: String = "",
    val clientId: String = "android-worker01-phone",
    val workerId: String = "worker01",
    val rememberSettings: Boolean = true
)

class MqttSettingsStore(private val context: Context) {
    private object Keys {
        val host = stringPreferencesKey("mqtt_host")
        val port = intPreferencesKey("mqtt_port")
        val username = stringPreferencesKey("mqtt_username")
        val password = stringPreferencesKey("mqtt_password")
        val clientId = stringPreferencesKey("mqtt_client_id")
        val workerId = stringPreferencesKey("worker_id")
        val remember = booleanPreferencesKey("mqtt_remember")
    }

    val settings: Flow<MqttSettings> = context.mqttDataStore.data.map { preferences ->
        MqttSettings(
            host = preferences[Keys.host].orEmpty(),
            port = preferences[Keys.port] ?: 1883,
            username = preferences[Keys.username].orEmpty(),
            password = preferences[Keys.password].orEmpty(),
            clientId = preferences[Keys.clientId] ?: "android-worker01-phone",
            workerId = preferences[Keys.workerId] ?: "worker01",
            rememberSettings = preferences[Keys.remember] ?: true
        )
    }

    suspend fun save(settings: MqttSettings) {
        context.mqttDataStore.edit { preferences ->
            preferences[Keys.remember] = settings.rememberSettings
            if (settings.rememberSettings) {
                preferences[Keys.host] = settings.host
                preferences[Keys.port] = settings.port
                preferences[Keys.username] = settings.username
                preferences[Keys.password] = settings.password
                preferences[Keys.clientId] = settings.clientId
                preferences[Keys.workerId] = settings.workerId
            } else {
                preferences.remove(Keys.host)
                preferences.remove(Keys.port)
                preferences.remove(Keys.username)
                preferences.remove(Keys.password)
                preferences.remove(Keys.clientId)
                preferences.remove(Keys.workerId)
            }
        }
    }
}
