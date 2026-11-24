package com.mignnlapin.jilu

import android.location.Location
import java.io.File
import kotlin.math.max

data class RestoredTrack(
    val points: List<TrackPoint>,
    val pointCount: Int,
    val distanceMeters: Double,
    val maxSpeedMps: Double,
    val elapsedSeconds: Double,
    val steps: Int,
    val lastAltitudeMeters: Double?,
    val lastAccuracyMeters: Float?,
    val lastProvider: String?
)

object TrackCsvReader {
    private const val MAX_ROUTE_POINTS = 4000

    fun read(file: File): RestoredTrack? {
        if (!file.exists() || !file.isFile) return null

        val rawPoints = ArrayList<TrackPoint>()
        var pointCount = 0
        var distanceMeters = 0.0
        var maxSpeedMps = 0.0
        var elapsedSeconds = 0.0
        var previousLat: Double? = null
        var previousLon: Double? = null
        var loggedDistance: Double? = null
        var loggedSteps: Int? = null
        var lastAltitudeMeters: Double? = null
        var lastAccuracyMeters: Float? = null
        var lastProvider: String? = null

        try {
            file.forEachLine { line ->
                if (line.isBlank() || line.startsWith("time_ms")) return@forEachLine
                val columns = line.split(',')
                if (columns.size < 8) return@forEachLine
                val latitude = columns[3].toDoubleOrNull() ?: return@forEachLine
                val longitude = columns[4].toDoubleOrNull() ?: return@forEachLine
                val elapsed = columns[2].toDoubleOrNull() ?: 0.0
                val speed = columns[6].toDoubleOrNull() ?: 0.0

                val previousLatValue = previousLat
                val previousLonValue = previousLon
                if (previousLatValue != null && previousLonValue != null) {
                    val result = FloatArray(1)
                    Location.distanceBetween(
                        previousLatValue,
                        previousLonValue,
                        latitude,
                        longitude,
                        result
                    )
                    distanceMeters += result[0]
                }
                previousLat = latitude
                previousLon = longitude

                pointCount++
                elapsedSeconds = max(elapsedSeconds, elapsed)
                maxSpeedMps = max(maxSpeedMps, speed)
                lastAltitudeMeters = columns[5].toDoubleOrNull()
                lastAccuracyMeters = columns[8].toFloatOrNull()
                lastProvider = columns.getOrNull(10)?.takeIf { it.isNotBlank() }
                loggedDistance = columns.getOrNull(11)?.toDoubleOrNull() ?: loggedDistance
                loggedSteps = columns.getOrNull(12)?.toIntOrNull() ?: loggedSteps
                rawPoints.add(TrackPoint(latitude, longitude))
            }
        } catch (_: Exception) {
            return null
        }

        if (pointCount == 0) return null

        val route = if (rawPoints.size <= MAX_ROUTE_POINTS) {
            rawPoints
        } else {
            val step = (rawPoints.size + MAX_ROUTE_POINTS - 1) / MAX_ROUTE_POINTS
            rawPoints.filterIndexed { index, _ ->
                index % step == 0 || index == rawPoints.lastIndex
            }
        }

        return RestoredTrack(
            points = route,
            pointCount = pointCount,
            distanceMeters = loggedDistance ?: distanceMeters,
            maxSpeedMps = maxSpeedMps,
            elapsedSeconds = elapsedSeconds,
            steps = loggedSteps ?: 0,
            lastAltitudeMeters = lastAltitudeMeters,
            lastAccuracyMeters = lastAccuracyMeters,
            lastProvider = lastProvider
        )
    }
}
