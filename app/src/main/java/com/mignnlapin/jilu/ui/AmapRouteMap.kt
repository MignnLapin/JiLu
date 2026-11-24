package com.mignnlapin.jilu.ui

import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.TextureMapView
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import com.mignnlapin.jilu.TrackPoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

private class MapOverlayState {
    var configured = false
    var polyline: Polyline? = null
    var startMarker: Marker? = null
    var endMarker: Marker? = null
    var lastRouteSize = 0
}

@Composable
fun AmapRouteMap(
    apiKey: String,
    route: List<TrackPoint>,
    tracking: Boolean,
    lineColor: Int,
    modifier: Modifier = Modifier,
    onTouchActive: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnTouchActive by rememberUpdatedState(onTouchActive)

    val mapView = remember(apiKey) {
        MapsInitializer.updatePrivacyShow(context, true, true)
        MapsInitializer.updatePrivacyAgree(context, true)
        MapsInitializer.setApiKey(apiKey)
        TextureMapView(context).apply {
            onCreate(Bundle())
        }
    }

    DisposableEffect(mapView, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    val overlayState = remember(mapView) { MapOverlayState() }
    var interacting by remember { mutableStateOf(false) }
    var lastInteractionAt by remember { mutableLongStateOf(0L) }

    Box(
        modifier = modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val pressed = event.changes.any { it.pressed }
                    // 地图内部先处理手势，这里再把事件标记为已消费，阻止外层页面滚动。
                    event.changes.forEach { it.consume() }
                    if (pressed != interacting) {
                        interacting = pressed
                        currentOnTouchActive(pressed)
                        if (!pressed) {
                            lastInteractionAt = SystemClock.elapsedRealtime()
                        }
                    }
                }
            }
        }
    ) {
        AndroidView(
            factory = {
                mapView.apply {
                    setOnTouchListener { view, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN ->
                                view.parent?.requestDisallowInterceptTouchEvent(true)

                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                                view.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                        false
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        ) { view ->
            val map = view.map
            if (!overlayState.configured) {
                map.uiSettings.isZoomControlsEnabled = false
                map.uiSettings.isCompassEnabled = false
                map.uiSettings.isScaleControlsEnabled = false
                overlayState.configured = true
            }

            if (route.isEmpty()) {
                overlayState.polyline?.remove()
                overlayState.polyline = null
                overlayState.startMarker?.remove()
                overlayState.startMarker = null
                overlayState.endMarker?.remove()
                overlayState.endMarker = null
                overlayState.lastRouteSize = 0
                return@AndroidView
            }

            val latLngs = route.map { it.toAmapLatLng() }
            val first = latLngs.first()
            val last = latLngs.last()

            if (overlayState.startMarker == null) {
                overlayState.startMarker = map.addMarker(
                    MarkerOptions().position(first).title("起点")
                )
            }
            if (overlayState.endMarker == null) {
                overlayState.endMarker = map.addMarker(
                    MarkerOptions().position(last).title(if (tracking) "当前位置" else "终点")
                )
            }
            overlayState.endMarker?.position = last

            if (overlayState.polyline == null) {
                overlayState.polyline = map.addPolyline(
                    PolylineOptions()
                        .width(12f)
                        .color(lineColor)
                        .addAll(latLngs)
                )
            } else {
                overlayState.polyline?.setPoints(latLngs)
            }

            val now = SystemClock.elapsedRealtime()
            val userBusy = interacting || now - lastInteractionAt < 5000L
            if (overlayState.lastRouteSize == 0) {
                fitRoute(map, latLngs)
            } else if (tracking && route.size > overlayState.lastRouteSize && !userBusy) {
                val zoom = max(map.cameraPosition?.zoom ?: 15f, 15f)
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(last, zoom))
            }
            overlayState.lastRouteSize = route.size
        }
    }
}

private fun fitRoute(map: AMap, points: List<LatLng>) {
    if (points.size == 1) {
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(points.first(), 16f))
        return
    }
    runCatching {
        val builder = LatLngBounds.builder()
        points.forEach { builder.include(it) }
        map.moveCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 80))
    }
}

private fun TrackPoint.toAmapLatLng(): LatLng {
    val (lat, lng) = GeoUtils.wgs84ToGcj02(latitude, longitude)
    return LatLng(lat, lng)
}

/**
 * 高德地图使用 GCJ-02 坐标，设备 GPS 输出 WGS-84，需要先转换再绘制。
 */
private object GeoUtils {
    private const val PI = Math.PI
    private const val SEMI_MAJOR_AXIS = 6378245.0
    private const val ECCENTRICITY_SQ = 0.00669342162296594323

    fun wgs84ToGcj02(latitude: Double, longitude: Double): Pair<Double, Double> {
        if (isOutsideChina(latitude, longitude)) return latitude to longitude
        var deltaLat = transformLatitude(longitude - 105.0, latitude - 35.0)
        var deltaLng = transformLongitude(longitude - 105.0, latitude - 35.0)
        val radLat = latitude / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - ECCENTRICITY_SQ * magic * magic
        val sqrtMagic = sqrt(magic)
        deltaLat = (deltaLat * 180.0) /
            ((SEMI_MAJOR_AXIS * (1 - ECCENTRICITY_SQ)) / (magic * sqrtMagic) * PI)
        deltaLng = (deltaLng * 180.0) /
            (SEMI_MAJOR_AXIS / sqrtMagic * cos(radLat) * PI)
        return (latitude + deltaLat) to (longitude + deltaLng)
    }

    private fun isOutsideChina(latitude: Double, longitude: Double): Boolean =
        longitude < 72.004 || longitude > 137.8347 ||
            latitude < 0.8293 || latitude > 55.8271

    private fun transformLatitude(x: Double, y: Double): Double {
        var result = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y +
            0.1 * x * y + 0.2 * sqrt(abs(x))
        result += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        result += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        result += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return result
    }

    private fun transformLongitude(x: Double, y: Double): Double {
        var result = 300.0 + x + 2.0 * y + 0.1 * x * x +
            0.1 * x * y + 0.1 * sqrt(abs(x))
        result += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        result += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        result += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return result
    }
}
