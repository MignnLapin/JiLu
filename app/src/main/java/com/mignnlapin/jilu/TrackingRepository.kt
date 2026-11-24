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
    val steps: Int = 0,
    val sensorLabel: String = "—",
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

    /** 启动时用系统缓存位置快速给出一个初始坐标，不计入距离和轨迹点。 */
    fun onSeed(location: Location) {
        _state.update { current ->
            if (!current.isTracking) {
                current
            } else {
                current.copy(
                    hasFix = true,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    altitudeMeters = if (location.hasAltitude()) location.altitude else null,
                    accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                    provider = location.provider,
                    error = null
                )
            }
        }
    }

    fun onFix(
        location: Location,
        speedMps: Double,
        distanceMeters: Double,
        elapsedSeconds: Double,
        steps: Int
    ) {
        _state.update { current ->
            val point = TrackPoint(location.latitude, location.longitude)
            val lastPoint = current.route.lastOrNull()
            val shouldAppend = lastPoint == null || distanceBetween(lastPoint, point) >= 1.0
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
                distanceMeters = distanceMeters,
                points = current.points + 1,
                steps = steps,
                elapsedSeconds = elapsedSeconds,
                averageSpeedMps = averageSpeed(distanceMeters, elapsedSeconds),
                route = if (shouldAppend) current.route + point else current.route,
                error = null
            )
        }
    }

    fun onMetrics(
        speedMps: Double,
        distanceMeters: Double,
        elapsedSeconds: Double,
        steps: Int
    ) {
        _state.update { current ->
            if (!current.isTracking) return@update current
            current.copy(
                speedMps = speedMps,
                maxSpeedMps = max(current.maxSpeedMps, speedMps),
                distanceMeters = distanceMeters,
                steps = steps,
                elapsedSeconds = elapsedSeconds,
                averageSpeedMps = averageSpeed(distanceMeters, elapsedSeconds)
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
            steps = track.steps,
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

    /** 显示当前使用的计步来源，便于排查室内无距离的问题。 */
    fun setSensorLabel(label: String) {
        _state.update { it.copy(sensorLabel = label) }
    }

    private fun averageSpeed(distanceMeters: Double, elapsedSeconds: Double): Double =
        if (elapsedSeconds > 0.5) distanceMeters / elapsedSeconds else 0.0

    private fun distanceBetween(a: TrackPoint, b: TrackPoint): Double {
        val results = FloatArray(1)
        Location.distanceBetween(
            a.latitude,
            a.longitude,
            b.latitude,
            b.longitude,
            results
        )
        return results[0].toDouble()
    }
}
