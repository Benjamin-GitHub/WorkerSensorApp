package com.example.workersensorapp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SensorReading(
    val ax: Float = 0f,
    val ay: Float = 0f,
    val az: Float = 0f,
    val gx: Float = 0f,
    val gy: Float = 0f,
    val gz: Float = 0f,
    val timestamp: Long = 0L,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationAccuracy: Float? = null,
    val locationTimestamp: Long? = null
)

data class SensorUiState(
    val isRunning: Boolean = false,
    val connectionStatus: String = "Disconnected",
    val packetsSent: Long = 0,
    val gpsEnabled: Boolean = false,
    val reading: SensorReading = SensorReading(),
    val message: String = "Enter the Raspberry Pi broker settings, then press Connect.",
    val isError: Boolean = false
)

object SensorAppState {
    private val mutableState = MutableStateFlow(SensorUiState())
    val state = mutableState.asStateFlow()

    fun update(block: (SensorUiState) -> SensorUiState) = mutableState.update(block)
}
