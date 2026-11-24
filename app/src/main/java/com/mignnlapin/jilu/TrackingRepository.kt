package com.mignnlapin.jilu

import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.max

data class TrackPoint(val latitude: Double, val longitude: Double)

data class TrackState(
    val isTracking: Boolean = false,
    val hasFix: Boolean = false,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitudeMeters: Double? = null,
    val accuracyMeters: Float? = null,
    val provider: String? = null,
    val speedMps: Double = 0.0,
    val maxSpeedMps: Double = 0.0,
    val averageSpeedMps: Double = 0.0,
    val distanceMeters: Double = 0.0,
    val points: Int = 0,
    val elapsedSeconds: Double = 0.0,
    val startElapsedRealtime: Long = 0L,
    val lastFilePath: String? = null,
    val route: List<TrackPoint> = emptyList(),
    val error: String? = null
)

object TrackingRepository {
    private val _state = MutableStateFlow(TrackState())
    val state: StateFlow<TrackState> = _state.asStateFlow()

    fun start(startElapsedRealtime: Long, filePath: String) {
        _state.value = TrackState(
            isTracking = true,
            startElapsedRealtime = startElapsedRealtime,
            lastFilePath = filePath
        )
    }

    fun onFix(location: Location, speedMps: Double, deltaMeters: Double, elapsedSeconds: Double) {
        _state.update { current ->
            val distance = current.distanceMeters + deltaMeters
            current.copy(
                isTracking = true,
                hasFix = true,
                latitude = location.latitude,
                longitude = location.longitude,
                altitudeMeters = if (location.hasAltitude()) location.altitude else null,
                accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                provider = location.provider,
                speedMps = speedMps,
                maxSpeedMps = max(current.maxSpeedMps, speedMps),
                distanceMeters = distance,
                points = current.points + 1,
                elapsedSeconds = elapsedSeconds,
                averageSpeedMps = if (elapsedSeconds > 0.5) distance / elapsedSeconds else 0.0,
                route = current.route + TrackPoint(location.latitude, location.longitude),
                error = null
            )
        }
    }

    fun restore(filePath: String, track: RestoredTrack) {
        if (_state.value.isTracking) return
        val last = track.points.lastOrNull() ?: return
        _state.value = TrackState(
            isTracking = false,
            hasFix = true,
            latitude = last.latitude,
            longitude = last.longitude,
            altitudeMeters = track.lastAltitudeMeters,
            accuracyMeters = track.lastAccuracyMeters,
            provider = track.lastProvider,
            speedMps = 0.0,
            maxSpeedMps = track.maxSpeedMps,
            averageSpeedMps = if (track.elapsedSeconds > 0.5) {
                track.distanceMeters / track.elapsedSeconds
            } else {
                0.0
            },
            distanceMeters = track.distanceMeters,
            points = track.pointCount,
            elapsedSeconds = track.elapsedSeconds,
            lastFilePath = filePath,
            route = track.points
        )
    }

    fun finish(filePath: String?, elapsedSeconds: Double) {
        _state.update {
            it.copy(
                isTracking = false,
                speedMps = 0.0,
                elapsedSeconds = elapsedSeconds,
                lastFilePath = filePath ?: it.lastFilePath
            )
        }
    }

    fun setError(message: String) {
        _state.update { it.copy(error = message) }
    }
}
