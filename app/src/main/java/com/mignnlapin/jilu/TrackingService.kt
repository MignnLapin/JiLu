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
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
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
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

class TrackingService : Service() {

    private lateinit var locationManager: LocationManager
    private lateinit var notificationManager: NotificationManager

    private var logger: TrackLogger? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var tracking = false
    private var startElapsedRealtime = 0L
    private var lastLocation: Location? = null
    private var lastNotificationAt = 0L

    // 轨迹 / 速度平滑
    private var gpsDistanceMeters = 0.0
    private var smoothedSpeedMps = 0.0
    private var lastSpeedSampleAt = 0L
    private val speedSamples = ArrayDeque<Double>()
    private var lastMovementAt = 0L

    // 计步（室内无卫星信号时估算距离与速度）
    private var sensorManager: SensorManager? = null
    private var stepSensorAvailable = false
    private var stepBaseline: Float? = null
    private var steps = 0
    private var stepDistanceMeters = 0.0
    private var lastStepAt = 0L
    private val stepTimes = ArrayDeque<Long>()

    private val tickerHandler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (!tracking) return
            publishMetrics()
            tickerHandler.postDelayed(this, 1000L)
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            handleLocation(location)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) = Unit
    }

    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!tracking || event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
            val total = event.values[0]
            val baseline = stepBaseline
            if (baseline == null) {
                stepBaseline = total
                return
            }
            if (total < baseline) {
                stepBaseline = total
                return
            }
            val newSteps = (total - baseline).toInt()
            if (newSteps <= steps) return

            val added = newSteps - steps
            steps = newSteps
            stepDistanceMeters = steps * STEP_LENGTH_METERS
            val now = SystemClock.elapsedRealtime()
            repeat(added.coerceAtMost(100)) { stepTimes.addLast(now) }
            lastStepAt = now
            lastMovementAt = now
            publishMetrics()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        notificationManager = getSystemService(NotificationManager::class.java)
        sensorManager = getSystemService(SensorManager::class.java)
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
        gpsDistanceMeters = 0.0
        smoothedSpeedMps = 0.0
        speedSamples.clear()
        steps = 0
        stepBaseline = null
        stepDistanceMeters = 0.0
        lastStepAt = 0L
        stepTimes.clear()
        startElapsedRealtime = SystemClock.elapsedRealtime()
        lastMovementAt = startElapsedRealtime
        lastSpeedSampleAt = startElapsedRealtime

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
        registerStepCounter()
        tickerHandler.removeCallbacks(ticker)
        tickerHandler.postDelayed(ticker, 1000L)
    }

    @SuppressLint("MissingPermission")
    private fun registerLocationUpdates() {
        val providers = buildList {
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                runCatching {
                    locationManager.allProviders.contains(LocationManager.FUSED_PROVIDER)
                }.getOrDefault(false)
            ) {
                add(LocationManager.FUSED_PROVIDER)
            }
        }

        var registered = false
        providers.forEach { provider ->
            val enabled = runCatching {
                locationManager.isProviderEnabled(provider)
            }.getOrDefault(false)
            if (!enabled) return@forEach
            registered = runCatching {
                locationManager.requestLocationUpdates(
                    provider,
                    1000L,
                    0f,
                    locationListener,
                    Looper.getMainLooper()
                )
            }.isSuccess || registered
        }

        if (!registered) {
            TrackingRepository.setError("定位服务不可用，请在系统设置中开启位置信息")
        }
        seedFromLastKnown()
    }

    @SuppressLint("MissingPermission")
    private fun seedFromLastKnown() {
        val nowWall = System.currentTimeMillis()
        val providers = buildList {
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(LocationManager.FUSED_PROVIDER)
            }
        }
        val recent = providers.mapNotNull { provider ->
            runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
        }.filter { nowWall - it.time in 0..10 * 60 * 1000L }
        val best = recent.filter { it.hasAccuracy() && it.accuracy <= 100f }
            .maxByOrNull { it.time }
            ?: recent.maxByOrNull { it.time }
        if (best != null) {
            TrackingRepository.onSeed(best)
        }
    }

    private fun handleLocation(location: Location) {
        if (!tracking) return
        if (location.time <= 0L) return

        val now = SystemClock.elapsedRealtime()
        val accuracy = if (location.hasAccuracy()) location.accuracy else 999f
        val previous = lastLocation

        var impliedSpeed = 0.0
        var moved = false

        if (previous != null && location.time > previous.time) {
            val dt = (location.time - previous.time) / 1000.0
            val distance = previous.distanceTo(location).toDouble()
            val previousAccuracy = if (previous.hasAccuracy()) previous.accuracy else 999f
            val accurate = accuracy <= 25f && previousAccuracy <= 25f
            val threshold = max(1.5, min(accuracy, previousAccuracy) * 0.6)
            if (accurate && dt >= 0.3 && distance in threshold..400.0) {
                val speed = distance / dt
                if (speed <= 55.0) {
                    // 仅采信精度较好的相邻点，避免网络定位漂移把距离放大。
                    gpsDistanceMeters += distance
                    impliedSpeed = speed
                    moved = true
                }
            }
        }

        var sample: Double? = null
        if (accuracy <= 30f) {
            val dopplerSpeed = if (location.hasSpeed()) {
                location.speed.toDouble()
            } else {
                -1.0
            }
            val dopplerReliable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                location.hasSpeedAccuracy()
            ) {
                location.speedAccuracyMetersPerSecond <= 4f
            } else {
                true
            }
            sample = when {
                dopplerSpeed >= 0.0 && dopplerReliable -> dopplerSpeed.coerceIn(0.0, 55.0)
                impliedSpeed > 0.0 -> impliedSpeed
                else -> 0.0
            }
        }
        if (sample != null) {
            speedSamples.addLast(sample)
            while (speedSamples.size > 3) speedSamples.removeFirst()
            val median = speedSamples.sorted()[speedSamples.size / 2]
            val seconds = ((now - lastSpeedSampleAt).coerceIn(500L, 5000L)) / 1000.0
            val alpha = 1.0 - exp(-seconds / 2.0)
            smoothedSpeedMps += alpha * (median - smoothedSpeedMps)
            lastSpeedSampleAt = now
        }

        if (moved) {
            lastMovementAt = now
        }

        lastLocation = location
        val elapsed = elapsedSeconds(now)
        val speed = liveSpeed(now)
        TrackingRepository.onFix(
            location = location,
            speedMps = speed,
            distanceMeters = liveDistanceMeters(),
            elapsedSeconds = elapsed,
            steps = steps
        )
        runCatching {
            logger?.log(
                location,
                elapsed,
                speed,
                liveDistanceMeters(),
                steps
            )
        }
        updateNotification(true)
    }

    private fun publishMetrics() {
        if (!tracking) return
        val now = SystemClock.elapsedRealtime()
        TrackingRepository.onMetrics(
            speedMps = liveSpeed(now),
            distanceMeters = liveDistanceMeters(),
            elapsedSeconds = elapsedSeconds(now),
            steps = steps
        )
        updateNotification()
    }

    private fun liveSpeed(now: Long): Double {
        if (now - lastMovementAt > STATIONARY_TIMEOUT_MS) {
            smoothedSpeedMps = 0.0
            speedSamples.clear()
        }
        val gpsSpeed = if (now - lastMovementAt > STATIONARY_TIMEOUT_MS) {
            0.0
        } else {
            smoothedSpeedMps
        }
        val stepSpeed = currentStepSpeed(now)
        return max(gpsSpeed, stepSpeed).coerceIn(0.0, 55.0)
    }

    private fun currentStepSpeed(now: Long): Double {
        if (!stepSensorAvailable) return 0.0
        stepTimes.removeAll { it < now - STEP_WINDOW_MS }
        if (now - lastStepAt > STATIONARY_TIMEOUT_MS) return 0.0
        val stepsPerSecond = stepTimes.size * 1000.0 / STEP_WINDOW_MS
        return (stepsPerSecond * STEP_LENGTH_METERS).coerceIn(0.0, 6.0)
    }

    private fun liveDistanceMeters(): Double {
        return if (stepSensorAvailable) {
            max(gpsDistanceMeters, stepDistanceMeters)
        } else {
            gpsDistanceMeters
        }
    }

    private fun elapsedSeconds(now: Long): Double =
        (now - startElapsedRealtime) / 1000.0

    private fun registerStepCounter() {
        val manager = sensorManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            stepSensorAvailable = false
            return
        }
        val sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: run {
            stepSensorAvailable = false
            return
        }
        stepSensorAvailable = runCatching {
            manager.registerListener(stepListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }.getOrDefault(false)
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
                "%.1f km/h · %.2f km · %d 步",
                state.speedMps * 3.6,
                state.distanceMeters / 1000.0,
                state.steps
            )
        } else {
            "正在获取定位…"
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
        runCatching { sensorManager?.unregisterListener(stepListener) }
        tickerHandler.removeCallbacks(ticker)
        releaseWakeLock()

        val elapsed = elapsedSeconds(SystemClock.elapsedRealtime())
        val path = logger?.file?.absolutePath
        logger?.close()
        logger = null
        path?.let { AppSettings.setLastTrackPath(this, it) }

        TrackingRepository.finish(path, elapsed)
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
        private const val STEP_LENGTH_METERS = 0.7
        private const val STEP_WINDOW_MS = 4000L
        private const val STATIONARY_TIMEOUT_MS = 3000L

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
