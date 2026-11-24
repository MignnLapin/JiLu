package com.mignnlapin.jilu

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TrackingService : Service() {

    private lateinit var locationManager: LocationManager
    private lateinit var notificationManager: NotificationManager

    private var logger: TrackLogger? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var tracking = false
    private var startElapsedRealtime = 0L
    private var lastLocation: Location? = null
    private var lastNotificationAt = 0L

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            handleLocation(location)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) = Unit
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTracking()
            ACTION_STOP -> stopTracking()
            else -> if (!tracking) stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (tracking) {
            stopTracking()
        }
        releaseWakeLock()
        super.onDestroy()
    }

    private fun startTracking() {
        if (tracking) return
        if (!hasLocationPermission()) {
            TrackingRepository.setError("缺少定位权限，无法开始记录")
            stopSelf()
            return
        }

        val directory = getExternalFilesDir("tracks") ?: File(filesDir, "tracks")
        if (!directory.exists() && !directory.mkdirs()) {
            TrackingRepository.setError("无法创建记录目录")
            stopSelf()
            return
        }

        val fileName = "track_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv"
        val file = File(directory, fileName)
        val newLogger = try {
            TrackLogger(file)
        } catch (e: Exception) {
            TrackingRepository.setError("无法写入记录文件：${e.message ?: "未知错误"}")
            stopSelf()
            return
        }

        logger = newLogger
        tracking = true
        lastLocation = null
        lastNotificationAt = 0L
        startElapsedRealtime = SystemClock.elapsedRealtime()

        TrackingRepository.start(startElapsedRealtime, file.absolutePath)
        AppSettings.setLastTrackPath(this, file.absolutePath)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
        acquireWakeLock()
        registerLocationUpdates()
    }

    @SuppressLint("MissingPermission")
    private fun registerLocationUpdates() {
        val gpsEnabled = runCatching {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }.getOrDefault(false)
        val networkEnabled = runCatching {
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)

        var registered = false
        if (gpsEnabled) {
            registered = runCatching {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    0f,
                    locationListener,
                    Looper.getMainLooper()
                )
            }.isSuccess || registered
        }
        if (networkEnabled) {
            registered = runCatching {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    2000L,
                    0f,
                    locationListener,
                    Looper.getMainLooper()
                )
            }.isSuccess || registered
        }

        if (!registered) {
            TrackingRepository.setError("定位服务不可用，请在系统设置中开启位置信息")
        }
    }

    private fun handleLocation(location: Location) {
        if (!tracking) return

        val elapsedSeconds = (SystemClock.elapsedRealtime() - startElapsedRealtime) / 1000.0
        val previous = lastLocation
        var deltaMeters = 0.0
        var speed = if (location.hasSpeed()) location.speed.toDouble() else 0.0

        if (previous != null) {
            val distance = previous.distanceTo(location).toDouble()
            val seconds = (location.time - previous.time) / 1000.0
            if (distance in 0.5..150.0) {
                deltaMeters = distance
            }
            if (speed <= 0.1 && seconds > 0.5 && distance < 300.0) {
                speed = distance / seconds
            }
        }
        if (speed.isNaN() || speed < 0.0 || speed > 150.0) {
            speed = 0.0
        }

        lastLocation = location
        TrackingRepository.onFix(location, speed, deltaMeters, elapsedSeconds)
        runCatching { logger?.log(location, elapsedSeconds, speed) }
        updateNotification()
    }

    private fun updateNotification(force: Boolean = false) {
        if (!tracking) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotificationAt < 2000L) return
        lastNotificationAt = now
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val state = TrackingRepository.state.value
        val text = if (state.hasFix) {
            String.format(
                Locale.US,
                "%.1f km/h · %.2f km · %d 个点",
                state.speedMps * 3.6,
                state.distanceMeters / 1000.0,
                state.points
            )
        } else {
            "正在获取卫星定位…"
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_track)
            .setContentTitle("JiLu · 正在记录")
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "结束记录", stopIntent)
            .build()
    }

    private fun stopTracking() {
        if (!tracking) {
            stopSelf()
            return
        }
        tracking = false
        runCatching { locationManager.removeUpdates(locationListener) }
        releaseWakeLock()

        val elapsedSeconds = (SystemClock.elapsedRealtime() - startElapsedRealtime) / 1000.0
        val path = logger?.file?.absolutePath
        logger?.close()
        logger = null
        path?.let { AppSettings.setLastTrackPath(this, it) }

        TrackingRepository.finish(path, elapsedSeconds)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "JiLu::Tracking"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "定位记录",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "记录位置与速度时显示的常驻通知"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.mignnlapin.jilu.action.START"
        private const val ACTION_STOP = "com.mignnlapin.jilu.action.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TrackingService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, TrackingService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
