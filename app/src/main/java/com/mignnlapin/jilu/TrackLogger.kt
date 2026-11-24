package com.mignnlapin.jilu

import android.location.Location
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TrackLogger(val file: File) : Closeable {

    private val writer = BufferedWriter(FileWriter(file, false))
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)

    init {
        writer.write(
            "time_ms,time_iso,elapsed_s,latitude,longitude,altitude_m," +
                "speed_mps,speed_kmh,accuracy_m,bearing,provider,distance_m,steps\n"
        )
        writer.flush()
    }

    fun log(
        location: Location,
        elapsedSeconds: Double,
        speedMps: Double,
        distanceMeters: Double,
        steps: Int
    ) {
        val time = if (location.time > 0L) location.time else System.currentTimeMillis()
        writer.write(
            buildString {
                append(time)
                append(',')
                append(isoFormat.format(Date(time)))
                append(',')
                append(format("%.2f", elapsedSeconds))
                append(',')
                append(format("%.7f", location.latitude))
                append(',')
                append(format("%.7f", location.longitude))
                append(',')
                append(if (location.hasAltitude()) format("%.1f", location.altitude) else "")
                append(',')
                append(format("%.3f", speedMps))
                append(',')
                append(format("%.2f", speedMps * 3.6))
                append(',')
                append(if (location.hasAccuracy()) format("%.1f", location.accuracy) else "")
                append(',')
                append(if (location.hasBearing()) format("%.1f", location.bearing) else "")
                append(',')
                append(location.provider ?: "")
                append(',')
                append(format("%.1f", distanceMeters))
                append(',')
                append(steps)
                append('\n')
            }
        )
        writer.flush()
    }

    private fun format(pattern: String, value: Number): String =
        String.format(Locale.US, pattern, value)

    override fun close() {
        runCatching { writer.flush() }
        runCatching { writer.close() }
    }
}
